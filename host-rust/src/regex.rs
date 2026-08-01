//! A minimal anchored regular expression matcher.
//!
//! The independent host has no dependencies, so the lexical classes of a
//! grammar artifact are matched by this engine. It supports literals, escapes,
//! `.`, character classes with ranges and negation, groups, alternation and the
//! greedy quantifiers `*`, `+`, `?` and `{n}` / `{n,}` / `{n,m}`, which is
//! exactly what canonical token declarations use.

#[derive(Clone, Debug)]
enum Node {
    Char(char),
    Any,
    Class(bool, Vec<(char, char)>),
    Concat(Vec<Node>),
    Alt(Vec<Node>),
    Repeat(Box<Node>, usize, Option<usize>),
}

#[derive(Clone, Debug)]
pub struct Regex {
    root: Node,
}

impl Regex {
    pub fn compile(pattern: &str) -> Result<Regex, String> {
        let characters: Vec<char> = pattern.chars().collect();
        let mut parser = Parser { characters, position: 0 };
        let root = parser.alternation()?;
        if parser.position != parser.characters.len() {
            return Err(format!("unexpected '{}' in pattern", parser.characters[parser.position]));
        }
        Ok(Regex { root })
    }

    /// The end offset of the greedy match anchored at `position`, if any.
    pub fn match_at(&self, text: &[char], position: usize) -> Option<usize> {
        matches(&self.root, text, position, &|end| Some(end))
    }
}

struct Parser {
    characters: Vec<char>,
    position: usize,
}

impl Parser {
    fn peek(&self) -> Option<char> {
        self.characters.get(self.position).copied()
    }

    fn alternation(&mut self) -> Result<Node, String> {
        let mut branches = vec![self.sequence()?];
        while self.peek() == Some('|') {
            self.position += 1;
            branches.push(self.sequence()?);
        }
        Ok(if branches.len() == 1 { branches.pop().unwrap() } else { Node::Alt(branches) })
    }

    fn sequence(&mut self) -> Result<Node, String> {
        let mut items = Vec::new();
        while let Some(character) = self.peek() {
            if character == '|' || character == ')' {
                break;
            }
            items.push(self.quantified()?);
        }
        Ok(Node::Concat(items))
    }

    fn quantified(&mut self) -> Result<Node, String> {
        let atom = self.atom()?;
        match self.peek() {
            Some('*') => {
                self.position += 1;
                Ok(Node::Repeat(Box::new(atom), 0, None))
            }
            Some('+') => {
                self.position += 1;
                Ok(Node::Repeat(Box::new(atom), 1, None))
            }
            Some('?') => {
                self.position += 1;
                Ok(Node::Repeat(Box::new(atom), 0, Some(1)))
            }
            Some('{') => self.bound(atom),
            _ => Ok(atom),
        }
    }

    /// Parses a bound quantifier `{n}`, `{n,}` or `{n,m}` following `atom`.
    /// A `{` that does not form a valid bound is not a quantifier here: it is
    /// left for the caller to consume as a literal character, matching how
    /// the reference host's regex engine treats a bare `{`.
    fn bound(&mut self, atom: Node) -> Result<Node, String> {
        let start = self.position;
        self.position += 1;
        let minimum = self.digits();
        if minimum.is_none() {
            self.position = start;
            return Ok(atom);
        }
        let minimum = minimum.unwrap();
        let maximum = if self.peek() == Some(',') {
            self.position += 1;
            let upper = self.digits();
            if self.peek() != Some('}') {
                self.position = start;
                return Ok(atom);
            }
            self.position += 1;
            upper
        } else if self.peek() == Some('}') {
            self.position += 1;
            Some(minimum)
        } else {
            self.position = start;
            return Ok(atom);
        };
        Ok(Node::Repeat(Box::new(atom), minimum, maximum))
    }

    /// Consumes a run of ASCII digits, if any, returning their value.
    fn digits(&mut self) -> Option<usize> {
        let start = self.position;
        while matches!(self.peek(), Some(c) if c.is_ascii_digit()) {
            self.position += 1;
        }
        if self.position == start {
            None
        } else {
            self.characters[start..self.position].iter().collect::<String>().parse().ok()
        }
    }

    fn atom(&mut self) -> Result<Node, String> {
        match self.peek() {
            None => Err("unexpected end of pattern".to_string()),
            Some('(') => {
                self.position += 1;
                // Non capturing by construction; `(?:` is accepted and ignored.
                if self.peek() == Some('?') {
                    self.position += 1;
                    if self.peek() == Some(':') {
                        self.position += 1;
                    }
                }
                let inner = self.alternation()?;
                if self.peek() != Some(')') {
                    return Err("unterminated group".to_string());
                }
                self.position += 1;
                Ok(inner)
            }
            Some('[') => self.class(),
            Some('.') => {
                self.position += 1;
                Ok(Node::Any)
            }
            Some('\\') => {
                self.position += 1;
                match self.peek() {
                    None => Err("unterminated escape".to_string()),
                    Some(escaped) => {
                        self.position += 1;
                        Ok(escape_node(escaped))
                    }
                }
            }
            Some(character) => {
                self.position += 1;
                Ok(Node::Char(character))
            }
        }
    }

    fn class(&mut self) -> Result<Node, String> {
        self.position += 1;
        let negated = self.peek() == Some('^');
        if negated {
            self.position += 1;
        }
        let mut ranges: Vec<(char, char)> = Vec::new();
        let mut first = true;
        loop {
            match self.peek() {
                None => return Err("unterminated character class".to_string()),
                Some(']') if !first => {
                    self.position += 1;
                    return Ok(Node::Class(negated, ranges));
                }
                Some(character) => {
                    first = false;
                    let low = if character == '\\' {
                        self.position += 1;
                        let escaped = self.peek().ok_or("unterminated escape")?;
                        self.position += 1;
                        match escape_node(escaped) {
                            Node::Char(c) => c,
                            Node::Class(false, mut inner) => {
                                ranges.append(&mut inner);
                                continue;
                            }
                            _ => return Err("unsupported escape in character class".to_string()),
                        }
                    } else {
                        self.position += 1;
                        character
                    };
                    // A '-' directly before ']' is a literal.
                    if self.peek() == Some('-')
                        && self.characters.get(self.position + 1).copied() != Some(']')
                        && self.characters.get(self.position + 1).is_some()
                    {
                        self.position += 1;
                        let high = self.peek().ok_or("unterminated range")?;
                        self.position += 1;
                        ranges.push((low, high));
                    } else {
                        ranges.push((low, low));
                    }
                }
            }
        }
    }
}

fn escape_node(escaped: char) -> Node {
    match escaped {
        'n' => Node::Char('\n'),
        't' => Node::Char('\t'),
        'r' => Node::Char('\r'),
        'd' => Node::Class(false, vec![('0', '9')]),
        'w' => Node::Class(false, vec![('a', 'z'), ('A', 'Z'), ('0', '9'), ('_', '_')]),
        's' => Node::Class(false, vec![(' ', ' '), ('\t', '\t'), ('\n', '\n'), ('\r', '\r')]),
        other => Node::Char(other),
    }
}

type Continuation<'a> = &'a dyn Fn(usize) -> Option<usize>;

fn matches(node: &Node, text: &[char], position: usize, next: Continuation) -> Option<usize> {
    match node {
        Node::Char(expected) => {
            if position < text.len() && text[position] == *expected {
                next(position + 1)
            } else {
                None
            }
        }
        Node::Any => {
            if position < text.len() && text[position] != '\n' {
                next(position + 1)
            } else {
                None
            }
        }
        Node::Class(negated, ranges) => {
            if position >= text.len() {
                return None;
            }
            let character = text[position];
            let inside = ranges.iter().any(|(low, high)| character >= *low && character <= *high);
            if inside != *negated {
                next(position + 1)
            } else {
                None
            }
        }
        Node::Concat(items) => concatenate(items, text, position, next),
        Node::Alt(branches) => branches.iter().find_map(|branch| matches(branch, text, position, next)),
        Node::Repeat(inner, minimum, maximum) => {
            repeat(inner, text, position, 0, *minimum, *maximum, next)
        }
    }
}

fn concatenate(items: &[Node], text: &[char], position: usize, next: Continuation) -> Option<usize> {
    match items.split_first() {
        None => next(position),
        Some((head, rest)) => matches(head, text, position, &|after| concatenate(rest, text, after, next)),
    }
}

fn repeat(
    inner: &Node,
    text: &[char],
    position: usize,
    count: usize,
    minimum: usize,
    maximum: Option<usize>,
    next: Continuation,
) -> Option<usize> {
    if maximum.map_or(true, |limit| count < limit) {
        let more = matches(inner, text, position, &|after| {
            if after == position {
                None
            } else {
                repeat(inner, text, after, count + 1, minimum, maximum, next)
            }
        });
        if more.is_some() {
            return more;
        }
    }
    if count >= minimum {
        next(position)
    } else {
        None
    }
}
