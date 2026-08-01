//! GrammarMachine0 for the independent host.
//!
//! It contains no syntax of its own: tokens, lexical classes, categories,
//! constructors, precedence through category layering and associativity through
//! `fold` all arrive as canonical data.

use crate::canon::{write_text, Canon};
use crate::regex::Regex;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug)]
pub struct TokenDef {
    pub name: String,
    pub kind: String,
    pub pattern: Regex,
}

#[derive(Clone, Debug)]
pub enum Element {
    Keyword(String),
    Bind(String, String),
}

#[derive(Clone, Debug)]
pub enum Production {
    Build { tag: String, elements: Vec<Element>, category: String },
    Pass { target: String, category: String },
    Fold { tag: String, target: String, category: String },
    Paren { open: String, target: String, close: String, category: String },
}

impl Production {
    fn category(&self) -> &str {
        match self {
            Production::Build { category, .. } => category,
            Production::Pass { category, .. } => category,
            Production::Fold { category, .. } => category,
            Production::Paren { category, .. } => category,
        }
    }
}

pub struct Grammar {
    pub start: String,
    pub tokens: Vec<TokenDef>,
    pub skips: Vec<Regex>,
    pub categories: Vec<(String, Vec<Production>)>,
    pub keywords: Vec<String>,
    pub token_names: BTreeSet<String>,
    pub reachable: BTreeMap<String, BTreeSet<String>>,
    pub reachable_open: BTreeMap<String, BTreeSet<String>>,
    pub tag_production: BTreeMap<String, Production>,
    pub layout: bool,
    /// Whether this grammar declares a token whose own pattern can match
    /// plain whitespace (YAML's `spaces`, for instance). Such a grammar
    /// never lets the lexer silently skip whitespace -- every run of it
    /// becomes a real, explicit token the tree captures -- so the printer
    /// must never invent a separating space of its own: on reparse that
    /// invented space would be lexed right back in as one more token the
    /// original tree never had, breaking the fixpoint. Grammars with no such
    /// token (the ordinary case) keep whitespace insignificant, so the
    /// printer's own single-space join is exactly the harmless,
    /// purely-cosmetic default it always was. Mirrors
    /// host-scala/grammar/GrammarMachine0.scala's `explicitWhitespace`.
    pub explicit_whitespace: bool,
}

fn closure_over(
    categories: &[(String, Vec<Production>)],
    direct: &BTreeMap<String, BTreeSet<String>>,
) -> BTreeMap<String, BTreeSet<String>> {
    let mut result = BTreeMap::new();
    for (name, _) in categories {
        let mut seen: BTreeSet<String> = BTreeSet::new();
        let mut stack = vec![name.clone()];
        while let Some(current) = stack.pop() {
            if seen.insert(current.clone()) {
                if let Some(targets) = direct.get(&current) {
                    targets.iter().for_each(|target| stack.push(target.clone()));
                }
            }
        }
        result.insert(name.clone(), seen);
    }
    result
}

pub fn load(value: &Canon) -> Result<Grammar, String> {
    let entries = match value {
        Canon::Node(tag, entries) if tag == "grammar" => entries,
        other => return Err(format!("not a grammar: {}", write_text(other))),
    };

    let mut start = String::new();
    let mut layout = false;
    let mut tokens: Vec<TokenDef> = Vec::new();
    let mut skips: Vec<Regex> = Vec::new();
    let mut categories: Vec<(String, Vec<Production>)> = Vec::new();

    for entry in entries {
        match entry {
            Canon::Node(tag, args) if tag == "name" && args.len() == 1 => {}
            Canon::Node(tag, args) if tag == "start" && args.len() == 1 => {
                start = args[0].as_sym().ok_or("start must be a symbol")?.to_string();
            }
            Canon::Node(tag, args) if tag == "skip" && args.len() == 1 => match &args[0] {
                Canon::Str(pattern) => skips.push(Regex::compile(pattern)?),
                _ => return Err("skip pattern must be a string".to_string()),
            },
            // `token layout ...` is a reserved marker, not a real token: see
            // the matching comment in host-scala/grammar/GrammarMachine0.scala
            // for why this rides on existing `token` syntax instead of adding
            // a new declaration to the grammar DSL.
            Canon::Node(tag, args)
                if tag == "token" && args.len() == 3 && args[0].as_sym() == Some("layout") =>
            {
                layout = true;
            }
            Canon::Node(tag, args) if tag == "token" && args.len() == 3 => {
                let name = args[0].as_sym().ok_or("token name must be a symbol")?.to_string();
                let kind = args[1].as_sym().ok_or("token kind must be a symbol")?.to_string();
                if !matches!(kind.as_str(), "sym" | "str" | "nat") {
                    return Err(format!("unknown token kind {}", kind));
                }
                let pattern = match &args[2] {
                    Canon::Str(text) => Regex::compile(text)?,
                    _ => return Err("token pattern must be a string".to_string()),
                };
                tokens.push(TokenDef { name, kind, pattern });
            }
            Canon::Node(tag, args) if tag == "category" && !args.is_empty() => {
                let name = args[0].as_sym().ok_or("category name must be a symbol")?.to_string();
                let mut productions = Vec::new();
                for production in &args[1..] {
                    productions.push(load_production(&name, production)?);
                }
                categories.push((name, productions));
            }
            other => return Err(format!("unknown grammar entry: {}", write_text(other))),
        }
    }

    if start.is_empty() {
        return Err("grammar has no start category".to_string());
    }

    let mut keywords: Vec<String> = Vec::new();
    for (_, productions) in &categories {
        for production in productions {
            match production {
                Production::Build { elements, .. } => {
                    for element in elements {
                        if let Element::Keyword(text) = element {
                            keywords.push(text.clone());
                        }
                    }
                }
                Production::Paren { open, close, .. } => {
                    keywords.push(open.clone());
                    keywords.push(close.clone());
                }
                _ => {}
            }
        }
    }
    keywords.sort();
    keywords.dedup();
    keywords.sort_by(|a, b| b.len().cmp(&a.len()));

    let mut direct: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    let mut direct_open: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for (name, productions) in &categories {
        let mut all = BTreeSet::new();
        let mut open = BTreeSet::new();
        for production in productions {
            match production {
                Production::Pass { target, .. } => {
                    all.insert(target.clone());
                    open.insert(target.clone());
                }
                Production::Fold { target, .. } => {
                    all.insert(target.clone());
                    open.insert(target.clone());
                }
                Production::Paren { target, .. } => {
                    all.insert(target.clone());
                }
                _ => {}
            }
        }
        direct.insert(name.clone(), all);
        direct_open.insert(name.clone(), open);
    }

    let mut tag_production: BTreeMap<String, Production> = BTreeMap::new();
    for (_, productions) in &categories {
        for production in productions {
            match production {
                Production::Build { tag, .. } | Production::Fold { tag, .. } => {
                    tag_production.entry(tag.clone()).or_insert_with(|| production.clone());
                }
                _ => {}
            }
        }
    }

    let token_names = tokens.iter().map(|token| token.name.clone()).collect();
    let reachable = closure_over(&categories, &direct);
    let reachable_open = closure_over(&categories, &direct_open);
    let space_char = [' '];
    let explicit_whitespace = tokens.iter().any(|token| token.pattern.match_at(&space_char, 0).is_some());

    Ok(Grammar {
        start,
        tokens,
        skips,
        categories,
        keywords,
        token_names,
        reachable,
        reachable_open,
        tag_production,
        layout,
        explicit_whitespace,
    })
}

fn load_production(category: &str, value: &Canon) -> Result<Production, String> {
    match value {
        Canon::Node(tag, args) if tag == "prod" && args.len() == 2 => {
            let name = args[0].as_sym().ok_or("production tag must be a symbol")?.to_string();
            let items = args[1].as_list().ok_or("production elements must be a list")?;
            let mut elements = Vec::new();
            for item in items {
                elements.push(load_element(item)?);
            }
            Ok(Production::Build { tag: name, elements, category: category.to_string() })
        }
        Canon::Node(tag, args) if tag == "pass" && args.len() == 1 => Ok(Production::Pass {
            target: args[0].as_sym().ok_or("pass target must be a symbol")?.to_string(),
            category: category.to_string(),
        }),
        Canon::Node(tag, args) if tag == "fold" && args.len() == 2 => Ok(Production::Fold {
            tag: args[0].as_sym().ok_or("fold tag must be a symbol")?.to_string(),
            target: args[1].as_sym().ok_or("fold target must be a symbol")?.to_string(),
            category: category.to_string(),
        }),
        Canon::Node(tag, args) if tag == "paren" && args.len() == 3 => {
            let open = match &args[0] {
                Canon::Str(text) => text.clone(),
                _ => return Err("paren opener must be a string".to_string()),
            };
            let close = match &args[2] {
                Canon::Str(text) => text.clone(),
                _ => return Err("paren closer must be a string".to_string()),
            };
            Ok(Production::Paren {
                open,
                target: args[1].as_sym().ok_or("paren target must be a symbol")?.to_string(),
                close,
                category: category.to_string(),
            })
        }
        other => Err(format!("unknown production: {}", write_text(other))),
    }
}

fn load_element(value: &Canon) -> Result<Element, String> {
    match value {
        Canon::Node(tag, args) if tag == "kw" && args.len() == 1 => match &args[0] {
            Canon::Str(text) => Ok(Element::Keyword(text.clone())),
            _ => Err("keyword must be a string".to_string()),
        },
        Canon::Node(tag, args) if tag == "bind" && args.len() == 2 => Ok(Element::Bind(
            args[0].as_sym().ok_or("bind field must be a symbol")?.to_string(),
            args[1].as_sym().ok_or("bind target must be a symbol")?.to_string(),
        )),
        other => Err(format!("unknown grammar element: {}", write_text(other))),
    }
}

// --------------------------------------------------------------------- lex

#[derive(Clone, Debug)]
pub struct Token {
    kind: String,
    text: String,
    value: Canon,
    offset: usize,
}

pub fn lex(grammar: &Grammar, input: &str) -> Result<Vec<Token>, String> {
    let characters: Vec<char> = input.chars().collect();
    // Offsets are reported in characters, matching the reference host.
    let mut out = Vec::new();
    let mut index = 0usize;
    while index < characters.len() {
        let explicit_whitespace_token = characters[index].is_whitespace()
            && grammar.tokens.iter().any(|token| {
                token.pattern
                    .match_at(&characters, index)
                    .is_some_and(|end| end > index)
            });
        if characters[index].is_whitespace() && !explicit_whitespace_token {
            index += 1;
            continue;
        }
        let mut skipped = 0usize;
        for skip in &grammar.skips {
            if let Some(end) = skip.match_at(&characters, index) {
                if end - index > skipped {
                    skipped = end - index;
                }
            }
        }
        if skipped > 0 {
            index += skipped;
            continue;
        }

        let mut best_length = 0usize;
        let mut best: Option<Token> = None;
        for token in &grammar.tokens {
            if let Some(end) = token.pattern.match_at(&characters, index) {
                let length = end - index;
                if length > best_length {
                    best_length = length;
                    let text: String = characters[index..end].iter().collect();
                    let value = match token.kind.as_str() {
                        "sym" => Canon::Sym(text.clone()),
                        "str" => Canon::Str(text.clone()),
                        _ => Canon::Nat(
                            crate::number::Nat::from_decimal(&text).ok_or("bad natural token")?,
                        ),
                    };
                    best = Some(Token { kind: token.name.clone(), text, value, offset: index });
                }
            }
        }
        for keyword in &grammar.keywords {
            let keyword_characters: Vec<char> = keyword.chars().collect();
            if keyword_characters.len() >= best_length
                && index + keyword_characters.len() <= characters.len()
                && characters[index..index + keyword_characters.len()] == keyword_characters[..]
            {
                best_length = keyword_characters.len();
                best = Some(Token {
                    kind: "kw".to_string(),
                    text: keyword.clone(),
                    value: Canon::Str(keyword.clone()),
                    offset: index,
                });
            }
        }

        match best {
            Some(token) => {
                out.push(token);
                index += best_length;
            }
            None => {
                return Err(format!(
                    "unexpected character '{}' at offset {}",
                    characters[index], index
                ))
            }
        }
    }
    Ok(if grammar.layout { apply_layout(&characters, out) } else { out })
}

/// The off-side rule, mirroring host-scala/grammar/GrammarMachine0.scala's
/// `applyLayout` exactly: see the comment there for the design. Offsets here
/// are character indices into `characters`, matching how this lexer already
/// counts them (not UTF-8 byte offsets).
const INDENT_TEXT: &str = "INDENT";
const DEDENT_TEXT: &str = "DEDENT";

fn column_of(characters: &[char], offset: usize) -> usize {
    if offset == 0 {
        return 0;
    }
    let mut i = offset;
    while i > 0 {
        i -= 1;
        if characters[i] == '\n' {
            return offset - i - 1;
        }
    }
    offset
}

fn layout_token(text: &str, offset: usize) -> Token {
    Token { kind: "kw".to_string(), text: text.to_string(), value: Canon::Str(text.to_string()), offset }
}

fn apply_layout(characters: &[char], tokens: Vec<Token>) -> Vec<Token> {
    let mut out = Vec::new();
    let mut stack: Vec<usize> = vec![0];

    if let Some(first) = tokens.first() {
        let col = column_of(characters, first.offset);
        if col > *stack.last().unwrap() {
            stack.push(col);
            out.push(layout_token(INDENT_TEXT, first.offset));
        }
    }

    let mut i = 0;
    while i < tokens.len() {
        // A token counts as ending its line if its text ends in a real
        // newline -- true for the `newline` token kind, but also for any
        // token whose own pattern absorbs a trailing newline (for example
        // Scala's line comments, which do exactly that to keep two
        // consecutive comments from merging when the printer re-joins them).
        let ends_line = tokens[i].text.ends_with('\n');
        out.push(tokens[i].clone());
        if ends_line {
            let mut j = i + 1;
            // Blank lines carry no structure of their own -- only the line
            // that follows them matters for the indent/dedent comparison --
            // so their newline tokens are dropped rather than passed
            // through, sparing every grammar from having to consume one
            // blank-line token per blank line just to keep matching.
            while j < tokens.len() && tokens[j].kind == "newline" {
                j += 1;
            }
            if j < tokens.len() {
                let col = column_of(characters, tokens[j].offset);
                if col > *stack.last().unwrap() {
                    stack.push(col);
                    out.push(layout_token(INDENT_TEXT, tokens[j].offset));
                } else {
                    while stack.len() > 1 && col < *stack.last().unwrap() {
                        stack.pop();
                        out.push(layout_token(DEDENT_TEXT, tokens[j].offset));
                    }
                }
            }
            i = j;
        } else {
            i += 1;
        }
    }

    while stack.len() > 1 {
        stack.pop();
        out.push(layout_token(DEDENT_TEXT, characters.len()));
    }

    out
}

// ------------------------------------------------------------------- parse

struct Parser<'a> {
    grammar: &'a Grammar,
    tokens: &'a [Token],
    position: usize,
    furthest: usize,
    // Packrat memoization: without it, a category tried at the same
    // position by several sibling alternatives that share a prefix (for
    // example Scala's several `header : headerDocumentTyped ...` block
    // forms) re-parses that whole shared prefix once per sibling, and that
    // cost multiplies with nesting depth -- deeply nested real code can make
    // an unmemoized parse take minutes. Parsing a given category from a
    // given position is a pure function of (position, category) here (no
    // external state affects it), so caching the outcome is
    // behavior-preserving and turns that multiplicative blowup back into
    // linear-ish work.
    memo: std::collections::HashMap<(usize, String), Option<(Canon, usize)>>,
}

impl<'a> Parser<'a> {
    fn advance(&mut self) {
        self.position += 1;
        if self.position > self.furthest {
            self.furthest = self.position;
        }
    }

    fn productions(&self, category: &str) -> Option<&'a Vec<Production>> {
        self.grammar
            .categories
            .iter()
            .find(|(name, _)| name == category)
            .map(|(_, productions)| productions)
    }

    fn category(&mut self, name: &str) -> Option<Canon> {
        let key = (self.position, name.to_string());
        if let Some(cached) = self.memo.get(&key) {
            return match cached {
                Some((value, end)) => {
                    self.position = *end;
                    Some(value.clone())
                }
                None => None,
            };
        }
        let start = self.position;
        let productions = self.productions(name)?;
        for production in productions {
            let saved = self.position;
            if let Some(value) = self.production(production) {
                self.memo.insert((start, name.to_string()), Some((value.clone(), self.position)));
                return Some(value);
            }
            self.position = saved;
        }
        self.memo.insert((start, name.to_string()), None);
        None
    }

    fn production(&mut self, production: &Production) -> Option<Canon> {
        match production {
            Production::Build { tag, elements, .. } => {
                let mut bound = Vec::new();
                for element in elements {
                    match element {
                        Element::Keyword(text) => {
                            if self.position < self.tokens.len()
                                && self.tokens[self.position].kind == "kw"
                                && &self.tokens[self.position].text == text
                            {
                                self.advance();
                            } else {
                                return None;
                            }
                        }
                        Element::Bind(field, target) => {
                            let _ = field;
                            match self.target(target) {
                                Some(value) => bound.push(value),
                                None => return None,
                            }
                        }
                    }
                }
                Some(Canon::Node(tag.clone(), bound))
            }
            Production::Pass { target, .. } => self.target(target),
            Production::Fold { tag, target, .. } => {
                let mut accumulator = self.target(target)?;
                loop {
                    let saved = self.position;
                    match self.target(target) {
                        Some(next) => {
                            accumulator = Canon::Node(tag.clone(), vec![accumulator, next]);
                        }
                        None => {
                            self.position = saved;
                            break;
                        }
                    }
                }
                Some(accumulator)
            }
            Production::Paren { open, target, close, .. } => {
                if self.position < self.tokens.len()
                    && self.tokens[self.position].kind == "kw"
                    && &self.tokens[self.position].text == open
                {
                    self.advance();
                    let inner = self.target(target)?;
                    if self.position < self.tokens.len()
                        && self.tokens[self.position].kind == "kw"
                        && &self.tokens[self.position].text == close
                    {
                        self.advance();
                        Some(inner)
                    } else {
                        None
                    }
                } else {
                    None
                }
            }
        }
    }

    fn target(&mut self, target: &str) -> Option<Canon> {
        if self.grammar.token_names.contains(target) {
            if self.position < self.tokens.len() && self.tokens[self.position].kind == target {
                let value = self.tokens[self.position].value.clone();
                self.advance();
                Some(value)
            } else {
                None
            }
        } else {
            self.category(target)
        }
    }
}

pub fn parse(grammar: &Grammar, input: &str) -> Result<Canon, String> {
    let tokens = lex(grammar, input)?;
    let mut parser =
        Parser { grammar, tokens: &tokens, position: 0, furthest: 0, memo: std::collections::HashMap::new() };
    let start = grammar.start.clone();
    match parser.category(&start) {
        Some(value) if parser.position == tokens.len() => Ok(value),
        Some(_) => Err(format!("unconsumed input at offset {}", tokens[parser.position].offset)),
        None => {
            let at = if parser.furthest < tokens.len() {
                format!("offset {}", tokens[parser.furthest].offset)
            } else {
                "end of input".to_string()
            };
            Err(format!("parse error at {}", at))
        }
    }
}

// ------------------------------------------------------------------- print

const CLOSERS: [&str; 7] = [")", "]", "}", ".", ",", ";", ":"];
const OPENERS: [&str; 3] = ["(", "[", "{"];

/// INDENT/DEDENT never print as literal text: they adjust a running depth,
/// and the token right after a real newline is prefixed with that many
/// levels of indent instead of the "no space" join a newline otherwise
/// gets. Grammars that never emit these two tokens keep level at 0, where
/// two spaces repeated zero times is the empty string this join already
/// produced -- so this is behaviorally identical to the original for every
/// existing grammar. Mirrors host-scala/grammar/GrammarMachine0.scala's
/// `join` exactly.
/// A printed piece of text, tagged with whether it came from a literal
/// keyword in a production (a real structural delimiter the grammar wrote
/// into the .grammar file, like Brace's `{`) versus an arbitrary captured
/// token value (whatever a language's own free-form token, like `word`,
/// happened to match). The bracket/punctuation spacing rules below only
/// make sense for the former: a bound token's text can coincidentally equal
/// "{" (for example Markdown prose containing a literal brace character)
/// without meaning "structural opener", and treating it as one would
/// suppress a separating space that the source actually had -- printing
/// `{targetKey` for `{ targetKey`, which then relexes as one word. Mirrors
/// host-scala/grammar/GrammarMachine0.scala's `Piece` exactly.
struct Piece {
    text: String,
    literal: bool,
}

pub fn print(grammar: &Grammar, value: &Canon) -> Result<String, String> {
    let mut out: Vec<Piece> = Vec::new();
    let start = grammar.start.clone();
    emit(grammar, value, &start, &mut out)?;
    let mut text = String::new();
    let mut level: usize = 0;
    let mut previous: Option<&Piece> = None;
    for piece in &out {
        let token = piece.text.as_str();
        if piece.literal && token == INDENT_TEXT {
            level += 1;
            continue;
        }
        if piece.literal && token == DEDENT_TEXT {
            level = level.saturating_sub(1);
            continue;
        }
        match previous {
            None => text.push_str(token),
            // A closer that starts a fresh line (for example a `}` closing
            // an indented block, or a fluent call chain's leading `.`)
            // still needs its line's indentation -- only a closer joining
            // the *same* line skips the separating space, so this check
            // must come after the newline case, not before it. "Ends with"
            // rather than exact-equals, because a line comment's own text
            // carries its trailing newline (see the matching note on the
            // layout trigger) rather than that newline being a separate
            // token.
            Some(p) if p.text.ends_with('\n') => {
                text.push_str(&"  ".repeat(level));
                text.push_str(token);
            }
            // A token that is itself entirely whitespace (for example
            // YAML's `spaces` atom, which carries its literal run of
            // indentation or separator spaces as its own text) already
            // provides whatever separation is needed on either side of it.
            // Adding this join's own separating space in front of it, or in
            // front of whatever follows it, would let two whitespace-only
            // sources stack -- one real space becomes three once reparsed,
            // since the extra spaces this join inserted get lexed right
            // back into the token's own run. So a whitespace-only token
            // joins directly onto its neighbour on either side, same as a
            // closer/opener would.
            Some(p) if is_all_whitespace(token) || is_all_whitespace(&p.text) => text.push_str(token),
            Some(p)
                if (piece.literal && CLOSERS.contains(&token))
                    || (p.literal && OPENERS.contains(&p.text.as_str())) =>
            {
                text.push_str(token)
            }
            // A grammar with explicit whitespace tokens (see
            // `explicit_whitespace` on Grammar) never has insignificant
            // space for this join to safely invent: whatever separation two
            // neighbouring pieces need must already be represented by an
            // atom somewhere in the tree (a `Space`, a `newline`, ...).
            // Falling through to the ordinary single-space join here would
            // add a space the tree never asked for, and reparsing would lex
            // it right back in as a real, unwanted token.
            Some(_) if grammar.explicit_whitespace => text.push_str(token),
            Some(_) => {
                text.push(' ');
                text.push_str(token);
            }
        }
        previous = Some(piece);
    }
    Ok(text)
}

fn is_all_whitespace(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| c.is_whitespace())
}

fn emit(grammar: &Grammar, value: &Canon, category: &str, out: &mut Vec<Piece>) -> Result<(), String> {
    match value {
        Canon::Node(tag, args) => {
            let production = grammar
                .tag_production
                .get(tag)
                .ok_or_else(|| format!("no production prints node tag {}", tag))?;
            let production_category = production.category().to_string();
            let open = grammar.reachable_open.get(category).cloned().unwrap_or_default();
            if open.contains(&production_category) {
                emit_production(grammar, production, args, out)
            } else {
                match find_paren(grammar, category) {
                    Some(Production::Paren { open, target, close, .. }) => {
                        out.push(Piece { text: open, literal: true });
                        emit(grammar, value, &target, out)?;
                        out.push(Piece { text: close, literal: true });
                        Ok(())
                    }
                    _ => Err(format!(
                        "cannot print {} in category {} without a bracketing production",
                        tag, category
                    )),
                }
            }
        }
        other => {
            out.push(Piece { text: primitive_text(other)?, literal: false });
            Ok(())
        }
    }
}

fn find_paren(grammar: &Grammar, category: &str) -> Option<Production> {
    let reach = grammar.reachable.get(category).cloned().unwrap_or_default();
    for (name, productions) in &grammar.categories {
        if !reach.contains(name) {
            continue;
        }
        for production in productions {
            if matches!(production, Production::Paren { .. }) {
                return Some(production.clone());
            }
        }
    }
    None
}

fn emit_production(
    grammar: &Grammar,
    production: &Production,
    args: &[Canon],
    out: &mut Vec<Piece>,
) -> Result<(), String> {
    match production {
        Production::Build { tag, elements, .. } => {
            let binds: Vec<&Element> = elements
                .iter()
                .filter(|element| matches!(element, Element::Bind(_, _)))
                .collect();
            if binds.len() != args.len() {
                return Err(format!(
                    "node {} has {} arguments but production binds {}",
                    tag,
                    args.len(),
                    binds.len()
                ));
            }
            let mut index = 0usize;
            for element in elements {
                match element {
                    Element::Keyword(text) => out.push(Piece { text: text.clone(), literal: true }),
                    Element::Bind(field, target) => {
                        let _ = field;
                        let value = &args[index];
                        index += 1;
                        if grammar.token_names.contains(target) {
                            out.push(Piece { text: primitive_text(value)?, literal: false });
                        } else {
                            emit(grammar, value, target, out)?;
                        }
                    }
                }
            }
            Ok(())
        }
        Production::Fold { tag, target, category } => {
            if args.len() != 2 {
                return Err(format!("fold node {} must have two arguments", tag));
            }
            emit(grammar, &args[0], category, out)?;
            emit(grammar, &args[1], target, out)
        }
        _ => Err("only build and fold productions print nodes".to_string()),
    }
}

fn primitive_text(value: &Canon) -> Result<String, String> {
    match value {
        Canon::Sym(text) => Ok(text.clone()),
        Canon::Str(text) => Ok(text.clone()),
        Canon::Nat(value) => Ok(value.to_decimal()),
        other => Err(format!("cannot print primitive {}", write_text(other))),
    }
}
