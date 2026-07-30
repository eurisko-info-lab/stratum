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
        if characters[index].is_whitespace() {
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
    Ok(out)
}

// ------------------------------------------------------------------- parse

struct Parser<'a> {
    grammar: &'a Grammar,
    tokens: &'a [Token],
    position: usize,
    furthest: usize,
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
        let productions = self.productions(name)?;
        for production in productions {
            let saved = self.position;
            if let Some(value) = self.production(production) {
                return Some(value);
            }
            self.position = saved;
        }
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
                        Element::Bind(_, target) => match self.target(target) {
                            Some(value) => bound.push(value),
                            None => return None,
                        },
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
    let mut parser = Parser { grammar, tokens: &tokens, position: 0, furthest: 0 };
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

const CLOSERS: [&str; 6] = [")", "]", "}", ".", ",", ";"];
const OPENERS: [&str; 3] = ["(", "[", "{"];

pub fn print(grammar: &Grammar, value: &Canon) -> Result<String, String> {
    let mut out: Vec<String> = Vec::new();
    let start = grammar.start.clone();
    emit(grammar, value, &start, &mut out)?;
    let mut text = String::new();
    for (index, token) in out.iter().enumerate() {
        if index == 0 {
            text.push_str(token);
        } else {
            let previous = &out[index - 1];
            if CLOSERS.contains(&token.as_str()) || OPENERS.contains(&previous.as_str()) {
                text.push_str(token);
            } else {
                text.push(' ');
                text.push_str(token);
            }
        }
    }
    Ok(text)
}

fn emit(grammar: &Grammar, value: &Canon, category: &str, out: &mut Vec<String>) -> Result<(), String> {
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
                        out.push(open);
                        emit(grammar, value, &target, out)?;
                        out.push(close);
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
            out.push(primitive_text(other)?);
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
    out: &mut Vec<String>,
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
                    Element::Keyword(text) => out.push(text.clone()),
                    Element::Bind(_, target) => {
                        let value = &args[index];
                        index += 1;
                        if grammar.token_names.contains(target) {
                            out.push(primitive_text(value)?);
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
