//! MetaMachine0 for the independent host.
//!
//! This is a second implementation of the same fixed calculus: the same
//! expression forms, the same pattern forms, the same primitives, the same
//! capability request shape, the same step accounting and the same canonical
//! verdict encoding. For identical inputs it must produce byte identical
//! verdicts to the reference host.

use crate::canon::{canonical_map, compare, decode, encode, write_text, Canon, Digest};
use crate::grammar;
use crate::number::{Int, Nat};
use crate::sha::sha256;
use crate::store::{digest_of, Artifact, Cas};
use std::cell::RefCell;
use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet, VecDeque};

#[derive(Clone, Debug)]
pub struct Fail {
    pub kind: String,
    pub message: String,
}

fn fail<T>(kind: &str, message: String) -> Result<T, Fail> {
    Err(Fail { kind: kind.to_string(), message })
}

pub type Eval = Result<Canon, Fail>;

// ------------------------------------------------------------------ budget

#[derive(Clone, Copy, Debug)]
pub struct Budget {
    pub steps: u64,
    pub depth: u32,
}

impl Budget {
    pub fn default_budget() -> Budget {
        Budget { steps: 2_000_000, depth: 4000 }
    }

    pub fn from_canon(value: &Canon) -> Option<Budget> {
        match value {
            Canon::Node(tag, args) if tag == "budget" => {
                let mut budget = Budget::default_budget();
                for arg in args {
                    match arg {
                        Canon::Node(name, inner) if name == "steps" && inner.len() == 1 => {
                            if let Canon::Nat(value) = &inner[0] {
                                budget.steps = value.to_u128()? as u64;
                            }
                        }
                        Canon::Node(name, inner) if name == "depth" && inner.len() == 1 => {
                            if let Canon::Nat(value) = &inner[0] {
                                budget.depth = value.to_u128()? as u32;
                            }
                        }
                        _ => {}
                    }
                }
                Some(budget)
            }
            _ => None,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Kernel {
    pub allow: BTreeSet<String>,
}

impl Kernel {
    pub fn from_canon(value: &Canon) -> Option<Kernel> {
        match value {
            Canon::Node(tag, args) if tag == "kernel" => {
                let mut allow = BTreeSet::new();
                for arg in args {
                    if let Canon::Node(name, names) = arg {
                        if name == "allow" {
                            for entry in names {
                                if let Canon::Sym(capability) = entry {
                                    allow.insert(capability.clone());
                                }
                            }
                        }
                    }
                }
                Some(Kernel { allow })
            }
            _ => None,
        }
    }
}

// ----------------------------------------------------------------- program

#[derive(Clone, Debug)]
pub struct Judgment {
    pub params: Vec<String>,
    pub body: Canon,
}

#[derive(Clone, Debug, Default)]
pub struct Program {
    pub judgments: BTreeMap<String, Judgment>,
}

impl Program {
    /// Loads a program, resolving `(use <ref>)` imports through the closure.
    ///
    /// Entries declared before an import override the imported judgments, which
    /// is the same precedence the reference host applies.
    pub fn load(value: &Canon, cas: &Cas) -> Result<Program, String> {
        let mut seen: BTreeSet<Digest> = BTreeSet::new();
        Program::load_with(value, cas, &mut seen)
    }

    fn load_with(value: &Canon, cas: &Cas, seen: &mut BTreeSet<Digest>) -> Result<Program, String> {
        let entries = match value {
            Canon::Node(tag, entries) if tag == "program" => entries,
            other => return Err(format!("not a meta program: {}", write_text(other))),
        };
        let mut accumulated = Program::default();
        for entry in entries {
            match entry {
                Canon::Node(tag, args) if tag == "module" && args.len() == 1 => {}
                Canon::Node(tag, args) if tag == "judgment" && args.len() == 3 => {
                    let name = args[0].as_sym().ok_or("judgment name must be a symbol")?.to_string();
                    let declared = args[1].as_list().ok_or("judgment parameters must be a list")?;
                    let mut params = Vec::new();
                    for parameter in declared {
                        params.push(
                            parameter
                                .as_sym()
                                .ok_or_else(|| format!("judgment {} has a non-symbol parameter", name))?
                                .to_string(),
                        );
                    }
                    accumulated
                        .judgments
                        .insert(name, Judgment { params, body: args[2].clone() });
                }
                Canon::Node(tag, args) if tag == "use" && args.len() == 1 => {
                    let reference = match &args[0] {
                        Canon::Ref(digest) => *digest,
                        _ => return Err("use requires a reference".to_string()),
                    };
                    if seen.contains(&reference) {
                        continue;
                    }
                    seen.insert(reference);
                    let artifact = cas
                        .get(&reference)
                        .map_err(|_| format!("missing imported program artifact {}", reference.hex()))?;
                    let imported = Program::load_with(&artifact.body, cas, seen)?;
                    let mut merged = imported.judgments;
                    for (name, judgment) in accumulated.judgments {
                        merged.insert(name, judgment);
                    }
                    accumulated = Program { judgments: merged };
                }
                other => return Err(format!("unknown program entry: {}", write_text(other))),
            }
        }
        Ok(accumulated)
    }
}

// ------------------------------------------------------------ capabilities

struct Environment {
    seed: String,
    counter: RefCell<u64>,
    secrets: RefCell<BTreeMap<String, String>>,
    queues: RefCell<BTreeMap<String, VecDeque<Canon>>>,
}

impl Environment {
    fn new(seed: &str) -> Environment {
        Environment {
            seed: seed.to_string(),
            counter: RefCell::new(0),
            secrets: RefCell::new(BTreeMap::new()),
            queues: RefCell::new(BTreeMap::new()),
        }
    }

    fn public_key(&self, secret: &str) -> String {
        let key = hex(&sha256(format!("pk:{}", secret).as_bytes()))[..16].to_string();
        self.secrets.borrow_mut().insert(key.clone(), secret.to_string());
        key
    }

    fn mac(&self, secret: &str, message: &Canon) -> Vec<u8> {
        let mut buffer = Vec::new();
        buffer.extend_from_slice(secret.as_bytes());
        buffer.push(0);
        buffer.extend_from_slice(&encode(message));
        sha256(&buffer).to_vec()
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{:02x}", byte)).collect()
}

fn ok(value: Canon) -> Canon {
    Canon::Node("ok".to_string(), vec![value])
}

fn denied(value: Canon) -> Canon {
    Canon::Node("denied".to_string(), vec![value])
}

// ------------------------------------------------------------------ engine

pub struct Engine<'a> {
    program: &'a Program,
    cas: &'a Cas,
    kernel: Kernel,
    budget: Budget,
    environment: Environment,
    steps: u64,
    calls: BTreeMap<String, u64>,
    capabilities: BTreeMap<String, u64>,
}

impl<'a> Engine<'a> {
    pub fn new(program: &'a Program, cas: &'a Cas, kernel: Kernel, budget: Budget, seed: &str) -> Engine<'a> {
        Engine {
            program,
            cas,
            kernel,
            budget,
            environment: Environment::new(seed),
            steps: 0,
            calls: BTreeMap::new(),
            capabilities: BTreeMap::new(),
        }
    }

    fn evidence(&self) -> Canon {
        let counts = |source: &BTreeMap<String, u64>| {
            Canon::Map(
                source
                    .iter()
                    .map(|(name, count)| (Canon::Sym(name.clone()), Canon::nat(*count as u128)))
                    .collect(),
            )
        };
        Canon::node(
            "evidence",
            vec![
                Canon::node("steps", vec![Canon::nat(self.steps as u128)]),
                Canon::node("calls", vec![counts(&self.calls)]),
                Canon::node("capabilities", vec![counts(&self.capabilities)]),
            ],
        )
    }

    fn tick(&mut self) -> Result<(), Fail> {
        self.steps += 1;
        if self.steps > self.budget.steps {
            return fail(
                "resource-exhausted",
                format!("budget of {} steps exhausted", self.budget.steps),
            );
        }
        Ok(())
    }

    pub fn derive(&mut self, goal: &Canon) -> Canon {
        let environment: BTreeMap<String, Canon> = BTreeMap::new();
        match self.eval(goal, &environment, 0) {
            Ok(value) => Canon::node(
                "verdict",
                vec![Canon::sym("ok"), value, self.evidence()],
            ),
            Err(failure) => Canon::node(
                "verdict",
                vec![
                    Canon::sym("error"),
                    Canon::Sym(failure.kind),
                    Canon::Str(failure.message),
                    self.evidence(),
                ],
            ),
        }
    }

    fn eval(&mut self, expression: &Canon, bindings: &BTreeMap<String, Canon>, depth: u32) -> Eval {
        self.tick()?;
        if depth > self.budget.depth {
            return fail("depth-exhausted", format!("depth budget {} exceeded", self.budget.depth));
        }
        let (tag, args) = match expression {
            Canon::Node(tag, args) => (tag.as_str(), args),
            other => {
                return fail("bad-expression", format!("not an expression: {}", write_text(other)))
            }
        };
        match (tag, args.len()) {
            ("q", 1) => Ok(args[0].clone()),
            ("v", 1) => match args[0].as_sym() {
                Some(name) => match bindings.get(name) {
                    Some(value) => Ok(value.clone()),
                    None => fail("unbound-variable", format!("unbound variable {}", name)),
                },
                None => fail(
                    "bad-expression",
                    format!("not an expression: {}", write_text(expression)),
                ),
            },
            ("mk", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let node_tag = args[0].as_sym().unwrap().to_string();
                let mut built = Vec::new();
                for argument in &args[1..] {
                    built.push(self.eval(argument, bindings, depth + 1)?);
                }
                Ok(Canon::Node(node_tag, built))
            }
            ("lst", _) => {
                let mut items = Vec::new();
                for argument in args {
                    items.push(self.eval(argument, bindings, depth + 1)?);
                }
                Ok(Canon::List(items))
            }
            ("mp", _) => {
                if args.len() % 2 != 0 {
                    return fail(
                        "bad-expression",
                        "map literal needs an even number of elements".to_string(),
                    );
                }
                let mut entries = Vec::new();
                let mut index = 0;
                while index < args.len() {
                    let key = self.eval(&args[index], bindings, depth + 1)?;
                    let value = self.eval(&args[index + 1], bindings, depth + 1)?;
                    entries.push((key, value));
                    index += 2;
                }
                Ok(canonical_map(entries))
            }
            ("if", 3) => match self.eval(&args[0], bindings, depth + 1)? {
                Canon::Bool(true) => self.eval(&args[1], bindings, depth + 1),
                Canon::Bool(false) => self.eval(&args[2], bindings, depth + 1),
                other => fail(
                    "type-error",
                    format!("if condition is not a boolean: {}", write_text(&other)),
                ),
            },
            ("let", 3) if args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap().to_string();
                let value = self.eval(&args[1], bindings, depth + 1)?;
                let mut extended = bindings.clone();
                extended.insert(name, value);
                self.eval(&args[2], &extended, depth + 1)
            }
            ("call", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap().to_string();
                let judgment = match self.program.judgments.get(&name) {
                    Some(judgment) => judgment.clone(),
                    None => return fail("unknown-judgment", format!("unknown judgment {}", name)),
                };
                let arguments = &args[1..];
                if judgment.params.len() != arguments.len() {
                    return fail(
                        "arity-error",
                        format!(
                            "judgment {} expects {} arguments, got {}",
                            name,
                            judgment.params.len(),
                            arguments.len()
                        ),
                    );
                }
                let mut evaluated = Vec::new();
                for argument in arguments {
                    evaluated.push(self.eval(argument, bindings, depth + 1)?);
                }
                *self.calls.entry(name).or_insert(0) += 1;
                let mut frame: BTreeMap<String, Canon> = BTreeMap::new();
                for (parameter, value) in judgment.params.iter().zip(evaluated) {
                    frame.insert(parameter.clone(), value);
                }
                self.eval(&judgment.body, &frame, depth + 1)
            }
            ("match", _) if !args.is_empty() => {
                let scrutinee = self.eval(&args[0], bindings, depth + 1)?;
                self.match_cases(&scrutinee, &args[1..], bindings, depth)
            }
            ("prim", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap().to_string();
                let mut evaluated = Vec::new();
                for argument in &args[1..] {
                    evaluated.push(self.eval(argument, bindings, depth + 1)?);
                }
                primitive(&name, &evaluated)
            }
            ("cap", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap().to_string();
                if !self.kernel.allow.contains(&name) {
                    return fail(
                        "capability-denied",
                        format!("capability {} is not constituted", name),
                    );
                }
                *self.capabilities.entry(name.clone()).or_insert(0) += 1;
                let mut evaluated = Vec::new();
                for argument in &args[1..] {
                    evaluated.push(self.eval(argument, bindings, depth + 1)?);
                }
                Ok(self.capability(&name, &evaluated))
            }
            ("fail", 2) if args[0].as_sym().is_some() => {
                let kind = args[0].as_sym().unwrap().to_string();
                let message = self.eval(&args[1], bindings, depth + 1)?;
                match message {
                    Canon::Str(text) => Err(Fail { kind, message: text }),
                    other => Err(Fail { kind, message: write_text(&other) }),
                }
            }
            _ => fail(
                "bad-expression",
                format!("not an expression: {}", write_text(expression)),
            ),
        }
    }

    fn match_cases(
        &mut self,
        value: &Canon,
        cases: &[Canon],
        bindings: &BTreeMap<String, Canon>,
        depth: u32,
    ) -> Eval {
        for case in cases {
            match case {
                Canon::Node(tag, args) if tag == "case" && args.len() == 2 => {
                    let mut extended = bindings.clone();
                    if self.match_pattern(&args[0], value, &mut extended)? {
                        return self.eval(&args[1], &extended, depth + 1);
                    }
                }
                other => {
                    return fail(
                        "bad-expression",
                        format!("not a match case: {}", write_text(other)),
                    )
                }
            }
        }
        fail("no-match", format!("no case matched {}", write_text(value)))
    }

    fn match_pattern(
        &mut self,
        pattern: &Canon,
        value: &Canon,
        bindings: &mut BTreeMap<String, Canon>,
    ) -> Result<bool, Fail> {
        self.tick()?;
        match pattern {
            Canon::Sym(name) if name == "_" => Ok(true),
            Canon::Node(tag, args) if tag == "pv" && args.len() == 1 && args[0].as_sym().is_some() => {
                bindings.insert(args[0].as_sym().unwrap().to_string(), value.clone());
                Ok(true)
            }
            Canon::Node(tag, args) if tag == "pq" && args.len() == 1 => Ok(&args[0] == value),
            Canon::Node(tag, args) if tag == "pm" && !args.is_empty() && args[0].as_sym().is_some() => {
                let expected = args[0].as_sym().unwrap();
                match value {
                    Canon::Node(actual, values)
                        if actual == expected && values.len() == args.len() - 1 =>
                    {
                        self.match_all(&args[1..], values, bindings)
                    }
                    _ => Ok(false),
                }
            }
            Canon::Node(tag, args) if tag == "pnode" && args.len() == 2 => match value {
                Canon::Node(actual, values) => {
                    let mut trial = bindings.clone();
                    if !self.match_pattern(&args[0], &Canon::Sym(actual.clone()), &mut trial)? {
                        return Ok(false);
                    }
                    if !self.match_pattern(&args[1], &Canon::List(values.clone()), &mut trial)? {
                        return Ok(false);
                    }
                    *bindings = trial;
                    Ok(true)
                }
                _ => Ok(false),
            },
            Canon::Node(tag, args) if tag == "pl" => match value {
                Canon::List(items) if items.len() == args.len() => {
                    self.match_all(args, items, bindings)
                }
                _ => Ok(false),
            },
            Canon::Node(tag, args) if tag == "pcons" && args.len() == 2 => match value {
                Canon::List(items) if !items.is_empty() => {
                    let mut trial = bindings.clone();
                    if !self.match_pattern(&args[0], &items[0], &mut trial)? {
                        return Ok(false);
                    }
                    let rest = Canon::List(items[1..].to_vec());
                    if !self.match_pattern(&args[1], &rest, &mut trial)? {
                        return Ok(false);
                    }
                    *bindings = trial;
                    Ok(true)
                }
                _ => Ok(false),
            },
            Canon::Node(tag, args) if tag == "pnil" && args.is_empty() => match value {
                Canon::List(items) if items.is_empty() => Ok(true),
                _ => Ok(false),
            },
            other => fail("bad-pattern", format!("not a pattern: {}", write_text(other))),
        }
    }

    fn match_all(
        &mut self,
        patterns: &[Canon],
        values: &[Canon],
        bindings: &mut BTreeMap<String, Canon>,
    ) -> Result<bool, Fail> {
        let mut trial = bindings.clone();
        for (pattern, value) in patterns.iter().zip(values.iter()) {
            if !self.match_pattern(pattern, value, &mut trial)? {
                return Ok(false);
            }
        }
        *bindings = trial;
        Ok(true)
    }

    fn capability(&mut self, name: &str, args: &[Canon]) -> Canon {
        match (name, args) {
            ("hash", [value]) => ok(Canon::Ref(digest_of(value))),
            ("digest-of-bytes", [Canon::Bytes(bytes)]) => {
                ok(Canon::Ref(Digest(sha256(bytes))))
            }
            ("cas-get", [Canon::Ref(reference)]) => match self.cas.get(reference) {
                Ok(artifact) => ok(artifact.to_canon()),
                Err(_) => denied(Canon::sym("missing-artifact")),
            },
            ("cas-has", [Canon::Ref(reference)]) => ok(Canon::Bool(self.cas.has(reference))),
            ("grammar-parse", [Canon::Ref(reference), Canon::Str(text)]) => {
                match self.grammar_of(reference).and_then(|g| grammar::parse(&g, text)) {
                    Ok(value) => ok(value),
                    Err(message) => denied(Canon::Str(message)),
                }
            }
            ("grammar-print", [Canon::Ref(reference), value]) => {
                match self.grammar_of(reference).and_then(|g| grammar::print(&g, value)) {
                    Ok(text) => ok(Canon::Str(text)),
                    Err(message) => denied(Canon::Str(message)),
                }
            }
            ("public-key", [Canon::Str(secret)]) => {
                ok(Canon::Str(self.environment.public_key(secret)))
            }
            ("sign", [Canon::Str(secret), message]) => {
                self.environment.public_key(secret);
                ok(Canon::Bytes(self.environment.mac(secret, message)))
            }
            ("verify", [Canon::Str(key), message, Canon::Bytes(signature)]) => {
                let secret = self.environment.secrets.borrow().get(key).cloned();
                match secret {
                    Some(secret) => {
                        ok(Canon::Bool(&self.environment.mac(&secret, message) == signature))
                    }
                    None => ok(Canon::Bool(false)),
                }
            }
            ("now", _) => {
                *self.environment.counter.borrow_mut() += 1;
                let counter = *self.environment.counter.borrow();
                ok(Canon::nat(counter as u128))
            }
            ("random-bytes", [Canon::Nat(count)]) => {
                *self.environment.counter.borrow_mut() += 1;
                let counter = *self.environment.counter.borrow();
                let digest = sha256(format!("{}:{}", self.environment.seed, counter).as_bytes());
                let take = count.to_u128().unwrap_or(0) as usize;
                ok(Canon::Bytes(digest[..take.min(32)].to_vec()))
            }
            ("open-connection", [Canon::Str(peer)]) => {
                self.environment.queues.borrow_mut().entry(peer.clone()).or_default();
                ok(Canon::Unit)
            }
            ("close-connection", [Canon::Str(peer)]) => {
                self.environment.queues.borrow_mut().remove(peer);
                ok(Canon::Unit)
            }
            ("send", [Canon::Str(peer), message]) => {
                self.environment
                    .queues
                    .borrow_mut()
                    .entry(peer.clone())
                    .or_default()
                    .push_back(message.clone());
                ok(Canon::Unit)
            }
            ("pending", [Canon::Str(peer)]) => {
                let size = self
                    .environment
                    .queues
                    .borrow_mut()
                    .entry(peer.clone())
                    .or_default()
                    .len();
                ok(Canon::nat(size as u128))
            }
            ("receive", [Canon::Str(peer)]) => {
                let message = self
                    .environment
                    .queues
                    .borrow_mut()
                    .entry(peer.clone())
                    .or_default()
                    .pop_front();
                match message {
                    Some(value) => ok(Canon::node("message", vec![value])),
                    None => ok(Canon::node("empty", vec![])),
                }
            }
            _ => denied(Canon::sym("bad-request")),
        }
    }

    fn grammar_of(&self, reference: &Digest) -> Result<grammar::Grammar, String> {
        let artifact = self
            .cas
            .get(reference)
            .map_err(|_| format!("missing grammar artifact {}", reference.hex()))?;
        grammar::load(&artifact.body)
    }
}

// -------------------------------------------------------------- primitives

fn number(value: &Canon) -> Result<i128, Fail> {
    match value {
        Canon::Nat(n) => n
            .to_u128()
            .and_then(|v| i128::try_from(v).ok())
            .ok_or_else(|| Fail {
                kind: "number-out-of-range".to_string(),
                message: "natural exceeds the arithmetic window of this host".to_string(),
            }),
        Canon::Int(z) => z.to_i128().ok_or_else(|| Fail {
            kind: "number-out-of-range".to_string(),
            message: "integer exceeds the arithmetic window of this host".to_string(),
        }),
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected a number, found {}", write_text(other)),
        }),
    }
}

fn make_number(value: i128) -> Canon {
    if value >= 0 {
        Canon::Nat(Nat::from_u128(value as u128))
    } else {
        Canon::Int(Int::from_i128(value))
    }
}

fn list_of(value: &Canon) -> Result<&Vec<Canon>, Fail> {
    match value {
        Canon::List(items) => Ok(items),
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected a list, found {}", write_text(other)),
        }),
    }
}

fn entries_of(value: &Canon) -> Result<&Vec<(Canon, Canon)>, Fail> {
    match value {
        Canon::Map(entries) => Ok(entries),
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected a map, found {}", write_text(other)),
        }),
    }
}

fn string_of(value: &Canon) -> Result<&String, Fail> {
    match value {
        Canon::Str(text) => Ok(text),
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected a string, found {}", write_text(other)),
        }),
    }
}

fn bytes_of(value: &Canon) -> Result<&Vec<u8>, Fail> {
    match value {
        Canon::Bytes(bytes) => Ok(bytes),
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected bytes, found {}", write_text(other)),
        }),
    }
}

fn index_of(value: &Canon) -> Result<i128, Fail> {
    number(value)
}

pub fn primitive(name: &str, args: &[Canon]) -> Eval {
    let arity = |expected: usize| -> Result<(), Fail> {
        if args.len() != expected {
            fail(
                "arity-error",
                format!("primitive {} expects {} arguments, got {}", name, expected, args.len()),
            )
        } else {
            Ok(())
        }
    };

    match name {
        "eq" => {
            arity(2)?;
            Ok(Canon::Bool(args[0] == args[1]))
        }
        "ne" => {
            arity(2)?;
            Ok(Canon::Bool(args[0] != args[1]))
        }
        "cmp" => {
            arity(2)?;
            Ok(Canon::nat(match compare(&args[0], &args[1]) {
                Ordering::Less => 0,
                Ordering::Equal => 1,
                Ordering::Greater => 2,
            }))
        }
        "add" => {
            arity(2)?;
            Ok(make_number(number(&args[0])? + number(&args[1])?))
        }
        "sub" => {
            arity(2)?;
            Ok(make_number(number(&args[0])? - number(&args[1])?))
        }
        "mul" => {
            arity(2)?;
            Ok(make_number(number(&args[0])? * number(&args[1])?))
        }
        "div" => {
            arity(2)?;
            let divisor = number(&args[1])?;
            if divisor == 0 {
                fail("division-by-zero", "div by zero".to_string())
            } else {
                Ok(make_number(number(&args[0])? / divisor))
            }
        }
        "mod" => {
            arity(2)?;
            let divisor = number(&args[1])?;
            if divisor == 0 {
                fail("division-by-zero", "mod by zero".to_string())
            } else {
                Ok(make_number(number(&args[0])? % divisor))
            }
        }
        "min" => {
            arity(2)?;
            Ok(make_number(number(&args[0])?.min(number(&args[1])?)))
        }
        "max" => {
            arity(2)?;
            Ok(make_number(number(&args[0])?.max(number(&args[1])?)))
        }
        "lt" => {
            arity(2)?;
            Ok(Canon::Bool(number(&args[0])? < number(&args[1])?))
        }
        "le" => {
            arity(2)?;
            Ok(Canon::Bool(number(&args[0])? <= number(&args[1])?))
        }
        "gt" => {
            arity(2)?;
            Ok(Canon::Bool(number(&args[0])? > number(&args[1])?))
        }
        "ge" => {
            arity(2)?;
            Ok(Canon::Bool(number(&args[0])? >= number(&args[1])?))
        }
        "not" => {
            arity(1)?;
            match &args[0] {
                Canon::Bool(value) => Ok(Canon::Bool(!value)),
                other => fail(
                    "type-error",
                    format!("not expects a boolean, found {}", write_text(other)),
                ),
            }
        }
        "and" => {
            arity(2)?;
            match (&args[0], &args[1]) {
                (Canon::Bool(a), Canon::Bool(b)) => Ok(Canon::Bool(*a && *b)),
                _ => fail("type-error", "and expects booleans".to_string()),
            }
        }
        "or" => {
            arity(2)?;
            match (&args[0], &args[1]) {
                (Canon::Bool(a), Canon::Bool(b)) => Ok(Canon::Bool(*a || *b)),
                _ => fail("type-error", "or expects booleans".to_string()),
            }
        }
        "is-unit" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Unit)))
        }
        "is-bool" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Bool(_))))
        }
        "is-nat" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Nat(_))))
        }
        "is-int" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Int(_))))
        }
        "is-bytes" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Bytes(_))))
        }
        "is-str" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Str(_))))
        }
        "is-sym" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Sym(_))))
        }
        "is-ref" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Ref(_))))
        }
        "is-list" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::List(_))))
        }
        "is-map" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Map(_))))
        }
        "is-node" => {
            arity(1)?;
            Ok(Canon::Bool(matches!(args[0], Canon::Node(_, _))))
        }
        "tag" => {
            arity(1)?;
            match &args[0] {
                Canon::Node(tag, _) => Ok(Canon::Sym(tag.clone())),
                other => fail(
                    "type-error",
                    format!("tag expects a node, found {}", write_text(other)),
                ),
            }
        }
        "args" => {
            arity(1)?;
            match &args[0] {
                Canon::Node(_, values) => Ok(Canon::List(values.clone())),
                other => fail(
                    "type-error",
                    format!("args expects a node, found {}", write_text(other)),
                ),
            }
        }
        "node" => {
            arity(2)?;
            match &args[0] {
                Canon::Sym(tag) => Ok(Canon::Node(tag.clone(), list_of(&args[1])?.clone())),
                other => fail(
                    "type-error",
                    format!("node expects a symbol tag, found {}", write_text(other)),
                ),
            }
        }
        "len" => {
            arity(1)?;
            Ok(Canon::nat(list_of(&args[0])?.len() as u128))
        }
        "nil?" => {
            arity(1)?;
            Ok(Canon::Bool(list_of(&args[0])?.is_empty()))
        }
        "cons" => {
            arity(2)?;
            let mut items = vec![args[0].clone()];
            items.extend(list_of(&args[1])?.iter().cloned());
            Ok(Canon::List(items))
        }
        "snoc" => {
            arity(2)?;
            let mut items = list_of(&args[0])?.clone();
            items.push(args[1].clone());
            Ok(Canon::List(items))
        }
        "head" => {
            arity(1)?;
            let items = list_of(&args[0])?;
            match items.first() {
                Some(value) => Ok(value.clone()),
                None => fail("empty-list", "head of empty list".to_string()),
            }
        }
        "tail" => {
            arity(1)?;
            let items = list_of(&args[0])?;
            if items.is_empty() {
                fail("empty-list", "tail of empty list".to_string())
            } else {
                Ok(Canon::List(items[1..].to_vec()))
            }
        }
        "nth" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let index = index_of(&args[1])?;
            if index < 0 || index >= items.len() as i128 {
                fail("index-out-of-range", format!("index {} out of range", index))
            } else {
                Ok(items[index as usize].clone())
            }
        }
        "append" => {
            arity(2)?;
            let mut items = list_of(&args[0])?.clone();
            items.extend(list_of(&args[1])?.iter().cloned());
            Ok(Canon::List(items))
        }
        "rev" => {
            arity(1)?;
            let mut items = list_of(&args[0])?.clone();
            items.reverse();
            Ok(Canon::List(items))
        }
        "take" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let count = index_of(&args[1])?.max(0) as usize;
            Ok(Canon::List(items.iter().take(count).cloned().collect()))
        }
        "drop" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let count = index_of(&args[1])?.max(0) as usize;
            Ok(Canon::List(items.iter().skip(count).cloned().collect()))
        }
        "sort" => {
            arity(1)?;
            let mut items = list_of(&args[0])?.clone();
            items.sort_by(compare);
            Ok(Canon::List(items))
        }
        "distinct" => {
            arity(1)?;
            let mut items: Vec<Canon> = Vec::new();
            for item in list_of(&args[0])? {
                if !items.contains(item) {
                    items.push(item.clone());
                }
            }
            Ok(Canon::List(items))
        }
        "contains" => {
            arity(2)?;
            Ok(Canon::Bool(list_of(&args[0])?.contains(&args[1])))
        }
        "index-of" => {
            arity(2)?;
            match list_of(&args[0])?.iter().position(|item| item == &args[1]) {
                Some(index) => Ok(Canon::node("some", vec![Canon::nat(index as u128)])),
                None => Ok(Canon::node("none", vec![])),
            }
        }
        "set-nth" => {
            arity(3)?;
            let items = list_of(&args[0])?;
            let index = index_of(&args[1])?;
            if index < 0 || index >= items.len() as i128 {
                fail("index-out-of-range", format!("index {} out of range", index))
            } else {
                let mut updated = items.clone();
                updated[index as usize] = args[2].clone();
                Ok(Canon::List(updated))
            }
        }
        "insert-at" => {
            arity(3)?;
            let items = list_of(&args[0])?;
            let index = index_of(&args[1])?;
            if index < 0 || index > items.len() as i128 {
                fail("index-out-of-range", format!("index {} out of range", index))
            } else {
                let mut updated = items.clone();
                updated.insert(index as usize, args[2].clone());
                Ok(Canon::List(updated))
            }
        }
        "remove-at" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let index = index_of(&args[1])?;
            if index < 0 || index >= items.len() as i128 {
                fail("index-out-of-range", format!("index {} out of range", index))
            } else {
                let mut updated = items.clone();
                updated.remove(index as usize);
                Ok(Canon::List(updated))
            }
        }
        "mnew" => {
            arity(0)?;
            Ok(Canon::Map(Vec::new()))
        }
        "msize" => {
            arity(1)?;
            Ok(Canon::nat(entries_of(&args[0])?.len() as u128))
        }
        "mhas" => {
            arity(2)?;
            Ok(Canon::Bool(entries_of(&args[0])?.iter().any(|(key, _)| key == &args[1])))
        }
        "mget" => {
            arity(3)?;
            Ok(entries_of(&args[0])?
                .iter()
                .find(|(key, _)| key == &args[1])
                .map(|(_, value)| value.clone())
                .unwrap_or_else(|| args[2].clone()))
        }
        "mput" => {
            arity(3)?;
            let mut entries: Vec<(Canon, Canon)> = entries_of(&args[0])?
                .iter()
                .filter(|(key, _)| key != &args[1])
                .cloned()
                .collect();
            entries.push((args[1].clone(), args[2].clone()));
            Ok(canonical_map(entries))
        }
        "mdel" => {
            arity(2)?;
            let entries: Vec<(Canon, Canon)> = entries_of(&args[0])?
                .iter()
                .filter(|(key, _)| key != &args[1])
                .cloned()
                .collect();
            Ok(canonical_map(entries))
        }
        "mkeys" => {
            arity(1)?;
            Ok(Canon::List(entries_of(&args[0])?.iter().map(|(key, _)| key.clone()).collect()))
        }
        "mvals" => {
            arity(1)?;
            Ok(Canon::List(entries_of(&args[0])?.iter().map(|(_, value)| value.clone()).collect()))
        }
        "mentries" => {
            arity(1)?;
            Ok(Canon::List(
                entries_of(&args[0])?
                    .iter()
                    .map(|(key, value)| Canon::node("entry", vec![key.clone(), value.clone()]))
                    .collect(),
            ))
        }
        "mfrom" => {
            arity(1)?;
            let mut entries = Vec::new();
            for item in list_of(&args[0])? {
                match item {
                    Canon::Node(tag, values) if tag == "entry" && values.len() == 2 => {
                        entries.push((values[0].clone(), values[1].clone()));
                    }
                    other => {
                        return fail(
                            "type-error",
                            format!("mfrom expects entry nodes, found {}", write_text(other)),
                        )
                    }
                }
            }
            Ok(canonical_map(entries))
        }
        "scat" => {
            arity(2)?;
            Ok(Canon::Str(format!("{}{}", string_of(&args[0])?, string_of(&args[1])?)))
        }
        "slen" => {
            arity(1)?;
            Ok(Canon::nat(string_of(&args[0])?.chars().count() as u128))
        }
        "ssub" => {
            arity(3)?;
            let text: Vec<char> = string_of(&args[0])?.chars().collect();
            let from = index_of(&args[1])?;
            let to = index_of(&args[2])?;
            if from < 0 || to > text.len() as i128 || from > to {
                fail("index-out-of-range", "substring out of range".to_string())
            } else {
                Ok(Canon::Str(text[from as usize..to as usize].iter().collect()))
            }
        }
        "sym->str" => {
            arity(1)?;
            match &args[0] {
                Canon::Sym(name) => Ok(Canon::Str(name.clone())),
                other => fail(
                    "type-error",
                    format!("sym->str expects a symbol, found {}", write_text(other)),
                ),
            }
        }
        "str->sym" => {
            arity(1)?;
            Ok(Canon::Sym(string_of(&args[0])?.clone()))
        }
        "nat->str" => {
            arity(1)?;
            match &args[0] {
                Canon::Nat(value) => Ok(Canon::Str(value.to_decimal())),
                Canon::Int(value) => Ok(Canon::Str(value.to_decimal())),
                other => fail(
                    "type-error",
                    format!("expected a number, found {}", write_text(other)),
                ),
            }
        }
        "str->nat" => {
            arity(1)?;
            let text = string_of(&args[0])?;
            match Nat::from_decimal(text) {
                Some(value) => Ok(Canon::Nat(value)),
                None => fail("parse-error", format!("not a natural number: {}", text)),
            }
        }
        "blen" => {
            arity(1)?;
            Ok(Canon::nat(bytes_of(&args[0])?.len() as u128))
        }
        "bcat" => {
            arity(2)?;
            let mut bytes = bytes_of(&args[0])?.clone();
            bytes.extend(bytes_of(&args[1])?.iter());
            Ok(Canon::Bytes(bytes))
        }
        "digest" => {
            arity(1)?;
            Ok(Canon::Ref(digest_of(&args[0])))
        }
        "encode" => {
            arity(1)?;
            Ok(Canon::Bytes(encode(&args[0])))
        }
        "decode" => {
            arity(1)?;
            match decode(bytes_of(&args[0])?) {
                Ok(value) => Ok(Canon::node("some", vec![value])),
                Err(_) => Ok(Canon::node("none", vec![])),
            }
        }
        "ref->str" => {
            arity(1)?;
            match &args[0] {
                Canon::Ref(digest) => Ok(Canon::Str(digest.hex())),
                other => fail(
                    "type-error",
                    format!("ref->str expects a reference, found {}", write_text(other)),
                ),
            }
        }
        other => fail("unknown-primitive", format!("unknown primitive {}", other)),
    }
}

/// Convenience wrapper used by the command surface.
pub fn derive(
    program: &Program,
    cas: &Cas,
    kernel: Kernel,
    budget: Budget,
    goal: &Canon,
    seed: &str,
) -> Canon {
    let mut engine = Engine::new(program, cas, kernel, budget, seed);
    engine.derive(goal)
}

/// The artifact helper used when reporting attestations.
pub fn artifact_digest(kind: &str, body: &Canon) -> Digest {
    Artifact { kind: kind.to_string(), body: body.clone() }.digest()
}
