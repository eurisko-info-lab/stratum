//! MetaMachine0 for the independent host.
//!
//! This is a second implementation of the same fixed calculus: the same
//! expression forms, the same pattern forms, the same primitives, the same
//! capability request shape, the same step accounting and the same canonical
//! verdict encoding. For identical inputs it must produce byte identical
//! verdicts to the reference host.

use std::hash::Hasher;
use std::rc::{Rc, Weak};
use crate::canon::{canonical_map, compare, decode, encode, write_text, Canon, Digest, List};
use crate::grammar;
use crate::number::{Int, Nat};
use crate::sha::sha256;
use crate::store::{digest_of, Cas};
use std::cell::RefCell;
use std::cmp::Ordering;
use std::collections::hash_map::DefaultHasher;
use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};

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
            Canon::Node(tag, args) if &**tag == "budget" => {
                let mut budget = Budget::default_budget();
                for arg in args.iter() {
                    match arg {
                        Canon::Node(name, inner) if &**name == "steps" && inner.len() == 1 => {
                            if let Canon::Nat(value) = &inner[0] {
                                budget.steps = value.to_u128()? as u64;
                            }
                        }
                        Canon::Node(name, inner) if &**name == "depth" && inner.len() == 1 => {
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
    pub allow: BTreeSet<Rc<str>>,
}

impl Kernel {
    pub fn from_canon(value: &Canon) -> Option<Kernel> {
        match value {
            Canon::Node(tag, args) if &**tag == "kernel" => {
                let mut allow = BTreeSet::new();
                for arg in args.iter() {
                    if let Canon::Node(name, names) = arg {
                        if &**name == "allow" {
                            for entry in names.iter() {
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
    pub params: Vec<Rc<str>>,
    pub body: Canon,
}

#[derive(Clone, Debug, Default)]
pub struct Program {
    pub judgments: NameMap<Rc<Judgment>>,
}

/// What names are bound to while a judgment body is evaluated.
///
/// A frame holds a judgment's parameters and whatever `let` has added, which
/// is a handful of entries and never more. It was a `BTreeMap<String, Canon>`,
/// and every attempted match case cloned one -- allocating a fresh `String`
/// per binding to decide something that usually failed. A short vector of
/// shared names clones by counting references, so a trial costs what looking
/// at it costs.
///
/// Later bindings shadow earlier ones, which is what inserting into a map did.
#[derive(Clone, Debug, Default)]
pub struct Bindings {
    entries: Vec<(Rc<str>, Canon)>,
}

impl Bindings {
    pub fn new() -> Bindings {
        Bindings { entries: Vec::new() }
    }

    pub fn with_capacity(size: usize) -> Bindings {
        Bindings { entries: Vec::with_capacity(size) }
    }

    pub fn get(&self, name: &str) -> Option<&Canon> {
        self.entries.iter().rev().find(|(bound, _)| &**bound == name).map(|(_, value)| value)
    }

    pub fn bind(&mut self, name: Rc<str>, value: Canon) {
        self.entries.push((name, value));
    }

    /// A copy with room to grow, so that binding into it does not reallocate.
    pub fn extended(&self, room: usize) -> Bindings {
        let mut entries = Vec::with_capacity(self.entries.len() + room);
        entries.extend(self.entries.iter().cloned());
        Bindings { entries }
    }

    pub fn mark(&self) -> usize {
        self.entries.len()
    }

    pub fn rewind(&mut self, mark: usize) {
        self.entries.truncate(mark);
    }
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
            Canon::Node(tag, entries) if &**tag == "program" => entries,
            other => return Err(format!("not a meta program: {}", write_text(other))),
        };
        let mut accumulated = Program::default();
        for entry in entries.iter() {
            match entry {
                Canon::Node(tag, args) if &**tag == "module" && args.len() == 1 => {}
                Canon::Node(tag, args) if &**tag == "judgment" && args.len() == 3 => {
                    let name = args[0].as_sym().ok_or("judgment name must be a symbol")?.to_string();
                    let declared = args[1].as_list().ok_or("judgment parameters must be a list")?;
                    let mut params = Vec::new();
                    for parameter in declared {
                        params.push(Rc::from(
                            parameter
                                .as_sym()
                                .ok_or_else(|| format!("judgment {} has a non-symbol parameter", name))?,
                        ));
                    }
                    accumulated
                        .judgments
                        .insert(name, Rc::new(Judgment { params, body: args[2].clone() }));
                }
                Canon::Node(tag, args) if &**tag == "use" && args.len() == 1 => {
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
    Canon::Node(Rc::from("ok".to_string()), Rc::new(vec![value]))
}

fn denied(value: Canon) -> Canon {
    Canon::Node(Rc::from("denied".to_string()), Rc::new(vec![value]))
}

// ------------------------------------------------------------------ engine

/// A small non-cryptographic hash for the interpreter's own tables.
///
/// The judgment table is looked up once per call and the call counter once
/// more, and the standard hasher is SipHash, which is chosen to resist an
/// adversary choosing keys. Nobody chooses these keys: they are the judgment
/// names in a program the host has already accepted. Multiplying and rotating
/// is enough, and it was an eighth of the running time.
#[derive(Default, Clone, Copy)]
pub struct NameHasher {
    state: u64,
}

impl std::hash::Hasher for NameHasher {
    fn write(&mut self, bytes: &[u8]) {
        for byte in bytes {
            self.state = (self.state ^ *byte as u64).wrapping_mul(0x0100_0000_01b3);
        }
    }

    fn finish(&self) -> u64 {
        self.state ^ (self.state >> 29)
    }
}

#[derive(Default, Clone, Copy)]
pub struct NameHash;

impl std::hash::BuildHasher for NameHash {
    type Hasher = NameHasher;
    fn build_hasher(&self) -> NameHasher {
        NameHasher { state: 0xcbf2_9ce4_8422_2325 }
    }
}

pub type NameMap<V> = HashMap<String, V, NameHash>;

pub struct Engine<'a> {
    program: &'a Program,
    cas: &'a Cas,
    kernel: Kernel,
    budget: Budget,
    environment: Environment,
    steps: u64,
    calls: NameMap<u64>,
    capabilities: NameMap<u64>,
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
            calls: NameMap::default(),
            capabilities: NameMap::default(),
        }
    }

    fn evidence(&self) -> Canon {
        // Counted in a hash map and reported in order, because the evidence is
        // part of what two hosts must agree on byte for byte.
        let counts = |source: &NameMap<u64>| {
            let mut entries: Vec<(&String, &u64)> = source.iter().collect();
            entries.sort_by(|a, b| a.0.cmp(b.0));
            Canon::map(
                entries
                    .into_iter()
                    .map(|(name, count)| (Canon::Sym(Rc::from(name.as_str())), Canon::nat(*count as u128)))
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
        let environment: Bindings = Bindings::new();
        match self.eval(goal, &environment, 0) {
            Ok(value) => Canon::node(
                "verdict",
                vec![Canon::sym("ok"), value, self.evidence()],
            ),
            Err(failure) => Canon::node(
                "verdict",
                vec![
                    Canon::sym("error"),
                    Canon::Sym(Rc::from(failure.kind)),
                    Canon::Str(Rc::from(failure.message)),
                    self.evidence(),
                ],
            ),
        }
    }

    fn eval(&mut self, expression: &Canon, bindings: &Bindings, depth: u32) -> Eval {
        self.tick()?;
        if depth > self.budget.depth {
            return fail("depth-exhausted", format!("depth budget {} exceeded", self.budget.depth));
        }
        let (tag, args) = match expression {
            Canon::Node(tag, args) => (&**tag, args),
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
                },                None => fail(
                    "bad-expression",
                    format!("not an expression: {}", write_text(expression)),
                ),
            },
            ("mk", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let node_tag = match &args[0] {
                    Canon::Sym(tag) => tag.clone(),
                    _ => unreachable!(),
                };
                let mut built = Vec::with_capacity(args.len() - 1);
                for argument in &args[1..] {
                    built.push(self.eval(argument, bindings, depth + 1)?);
                }
                Ok(Canon::Node(node_tag, Rc::new(built)))
            }
            ("lst", _) => {
                let mut items = Vec::new();
                for argument in args.iter() {
                    items.push(self.eval(argument, bindings, depth + 1)?);
                }
                Ok(Canon::list(items))
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
                let name = match &args[0] {
                    Canon::Sym(name) => name.clone(),
                    _ => unreachable!(),
                };
                let value = self.eval(&args[1], bindings, depth + 1)?;
                let mut extended = bindings.extended(1);
                extended.bind(name, value);
                self.eval(&args[2], &extended, depth + 1)
            }
            ("call", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap();
                let judgment = match self.program.judgments.get(name) {
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
                let mut frame = Bindings::with_capacity(arguments.len());
                for (parameter, argument) in judgment.params.iter().zip(arguments) {
                    let value = self.eval(argument, bindings, depth + 1)?;
                    frame.bind(parameter.clone(), value);
                }
                match self.calls.get_mut(name) {
                    Some(count) => *count += 1,
                    None => {
                        self.calls.insert(name.to_string(), 1);
                    }
                }
                self.eval(&judgment.body, &frame, depth + 1)
            }
            ("match", _) if !args.is_empty() => {
                let scrutinee = self.eval(&args[0], bindings, depth + 1)?;
                self.match_cases(&scrutinee, &args[1..], bindings, depth)
            }
            ("prim", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap();
                let mut evaluated = Vec::with_capacity(args.len() - 1);
                for argument in &args[1..] {
                    evaluated.push(self.eval(argument, bindings, depth + 1)?);
                }
                primitive(name, &evaluated)
            }
            ("cap", _) if !args.is_empty() && args[0].as_sym().is_some() => {
                let name = args[0].as_sym().unwrap();
                if !self.kernel.allow.iter().any(|allowed| &**allowed == name) {
                    return fail(
                        "capability-denied",
                        format!("capability {} is not constituted", name),
                    );
                }
                match self.capabilities.get_mut(name) {
                    Some(count) => *count += 1,
                    None => {
                        self.capabilities.insert(name.to_string(), 1);
                    }
                }
                let mut evaluated = Vec::with_capacity(args.len() - 1);
                for argument in &args[1..] {
                    evaluated.push(self.eval(argument, bindings, depth + 1)?);
                }
                let name = args[0].as_sym().unwrap().to_string();
                Ok(self.capability(&name, &evaluated))
            }
            ("fail", 2) if args[0].as_sym().is_some() => {
                let kind = args[0].as_sym().unwrap().to_string();
                let message = self.eval(&args[1], bindings, depth + 1)?;
                match message {
                    Canon::Str(text) => Err(Fail { kind, message: text.to_string() }),
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
        bindings: &Bindings,
        depth: u32,
    ) -> Eval {
        // One frame for the whole match, rewound between cases. Cloning it per
        // case was cloning what usually did not match.
        let mut extended = bindings.extended(4);
        let mark = extended.mark();
        for case in cases {
            match case {
                Canon::Node(tag, args) if &**tag == "case" && args.len() == 2 => {
                    if self.match_pattern(&args[0], value, &mut extended)? {
                        return self.eval(&args[1], &extended, depth + 1);
                    }
                    extended.rewind(mark);
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
        bindings: &mut Bindings,
    ) -> Result<bool, Fail> {
        self.tick()?;
        match pattern {
            Canon::Sym(name) if &**name == "_" => Ok(true),
            Canon::Node(tag, args) if &**tag == "pv" && args.len() == 1 && args[0].as_sym().is_some() => {
                match &args[0] {
                    Canon::Sym(name) => bindings.bind(name.clone(), value.clone()),
                    _ => unreachable!(),
                }
                Ok(true)
            }
            Canon::Node(tag, args) if &**tag == "pq" && args.len() == 1 => Ok(&args[0] == value),
            Canon::Node(tag, args) if &**tag == "pm" && !args.is_empty() && args[0].as_sym().is_some() => {
                let expected = args[0].as_sym().unwrap();
                match value {
                    Canon::Node(actual, values)
                        if &**actual == expected && values.len() == args.len() - 1 =>
                    {
                        self.match_all(&args[1..], values, bindings)
                    }
                    _ => Ok(false),
                }
            }
            Canon::Node(tag, args) if &**tag == "pnode" && args.len() == 2 => match value {
                Canon::Node(actual, values) => {
                    let mark = bindings.mark();
                    if !self.match_pattern(&args[0], &Canon::Sym(actual.clone()), bindings)? {
                        bindings.rewind(mark);
                        return Ok(false);
                    }
                    if !self.match_pattern(
                        &args[1],
                        &Canon::List(List::shared(values.clone())),
                        bindings,
                    )? {
                        bindings.rewind(mark);
                        return Ok(false);
                    }
                    Ok(true)
                }
                _ => Ok(false),
            },
            Canon::Node(tag, args) if &**tag == "pl" => match value {
                Canon::List(items) if items.len() == args.len() => {
                    self.match_all(args, items, bindings)
                }
                _ => Ok(false),
            },
            Canon::Node(tag, args) if &**tag == "pcons" && args.len() == 2 => match value {
                Canon::List(items) if !items.is_empty() => {
                    let mark = bindings.mark();
                    if !self.match_pattern(&args[0], &items[0], bindings)? {
                        bindings.rewind(mark);
                        return Ok(false);
                    }
                    let rest = Canon::List(items.tail());
                    if !self.match_pattern(&args[1], &rest, bindings)? {
                        bindings.rewind(mark);
                        return Ok(false);
                    }
                    Ok(true)
                }
                _ => Ok(false),
            },
            Canon::Node(tag, args) if &**tag == "pnil" && args.is_empty() => match value {
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
        bindings: &mut Bindings,
    ) -> Result<bool, Fail> {
        let mark = bindings.mark();
        for (pattern, value) in patterns.iter().zip(values.iter()) {
            if !self.match_pattern(pattern, value, bindings)? {
                bindings.rewind(mark);
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn capability(&mut self, name: &str, args: &[Canon]) -> Canon {
        match (name, args) {
            ("hash", [value]) => ok(Canon::Ref(digest_of(value))),
            ("digest-of-bytes", [Canon::Bytes(bytes)]) => {
                ok(Canon::Ref(Digest(sha256(bytes))))
            }
            ("text-octets", [Canon::Str(text)]) => ok(Canon::list(
                text.as_bytes().iter().map(|b| Canon::nat(*b as u128)).collect(),
            )),
            ("octets-of-bytes", [Canon::Bytes(bytes)]) => ok(Canon::list(
                bytes.iter().map(|b| Canon::nat(*b as u128)).collect(),
            )),
            ("bytes-of-octets", [Canon::List(items)]) => {
                let mut octets = Vec::with_capacity(items.len());
                for item in items.iter() {
                    match item {
                        Canon::Nat(value) => match value.to_u128() {
                            Some(value) if value < 256 => octets.push(value as u8),
                            _ => return denied(Canon::sym("not-an-octet")),
                        },
                        _ => return denied(Canon::sym("not-an-octet")),
                    }
                }
                ok(Canon::Bytes(Rc::new(octets)))
            }
            ("cas-get", [Canon::Ref(reference)]) => match self.cas.get(reference) {
                Ok(artifact) => ok(artifact.to_canon()),
                Err(_) => denied(Canon::sym("missing-artifact")),
            },
            ("cas-has", [Canon::Ref(reference)]) => ok(Canon::Bool(self.cas.has(reference))),
            ("grammar-parse", [Canon::Ref(reference), Canon::Str(text)]) => {
                match self.grammar_of(reference).and_then(|g| grammar::parse(&g, text)) {
                    Ok(value) => ok(value),
                    Err(message) => denied(Canon::Str(Rc::from(message))),
                }
            }
            ("grammar-print", [Canon::Ref(reference), value]) => {
                match self.grammar_of(reference).and_then(|g| grammar::print(&g, value)) {
                    Ok(text) => ok(Canon::Str(Rc::from(text))),
                    Err(message) => denied(Canon::Str(Rc::from(message))),
                }
            }
            ("public-key", [Canon::Str(secret)]) => {
                ok(Canon::Str(Rc::from(self.environment.public_key(secret))))
            }
            ("sign", [Canon::Str(secret), message]) => {
                self.environment.public_key(secret);
                ok(Canon::Bytes(Rc::new(self.environment.mac(secret, message))))
            }
            ("verify", [Canon::Str(key), message, Canon::Bytes(signature)]) => {
                let secret = self.environment.secrets.borrow().get(&**key).cloned();
                match secret {
                    Some(secret) => {
                        ok(Canon::Bool(self.environment.mac(&secret, message) == **signature))
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
                ok(Canon::Bytes(Rc::new(digest[..take.min(32)].to_vec())))
            }
            ("open-connection", [Canon::Str(peer)]) => {
                self.environment.queues.borrow_mut().entry(peer.clone().to_string()).or_default();
                ok(Canon::Unit)
            }
            ("close-connection", [Canon::Str(peer)]) => {
                self.environment.queues.borrow_mut().remove(&**peer);
                ok(Canon::Unit)
            }
            ("send", [Canon::Str(peer), message]) => {
                self.environment
                    .queues
                    .borrow_mut()
                    .entry(peer.clone().to_string())
                    .or_default()
                    .push_back(message.clone());
                ok(Canon::Unit)
            }
            ("pending", [Canon::Str(peer)]) => {
                let size = self
                    .environment
                    .queues
                    .borrow_mut()
                    .entry(peer.clone().to_string())
                    .or_default()
                    .len();
                ok(Canon::nat(size as u128))
            }
            ("receive", [Canon::Str(peer)]) => {
                let message = self
                    .environment
                    .queues
                    .borrow_mut()
                    .entry(peer.clone().to_string())
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

fn list_of(value: &Canon) -> Result<&List, Fail> {
    match value {
        Canon::List(items) => Ok(items),
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected a list, found {}", write_text(other)),
        }),
    }
}

#[derive(Clone, Debug)]
enum HamtNode {
    Empty,
    BitmapIndexed { bitmap: u32, children: Vec<Rc<HamtNode>> },
    Leaf { hash: u64, key: Canon, value: Canon },
    Collision { hash: u64, entries: Vec<(Canon, Canon)> },
}

#[derive(Clone, Debug)]
struct HamtMap {
    root: Rc<HamtNode>,
    size: usize,
}

impl HamtMap {
    const BITS_PER_LEVEL: u32 = 5;
    const MASK: u64 = (1 << Self::BITS_PER_LEVEL) - 1;
    const MAX_SHIFT: u32 = 64;

    fn empty() -> HamtMap {
        HamtMap { root: Rc::new(HamtNode::Empty), size: 0 }
    }

    fn hash_key(key: &Canon) -> u64 {
        let mut hasher = DefaultHasher::new();
        hasher.write(&encode(key));
        hasher.finish()
    }

    fn mask(hash: u64, shift: u32) -> u32 {
        ((hash >> shift) & Self::MASK) as u32
    }

    fn bitpos(mask: u32) -> u32 {
        1 << mask
    }

    fn index(bitmap: u32, bit: u32) -> usize {
        (bitmap & (bit - 1)).count_ones() as usize
    }

    fn contains(&self, key: &Canon) -> bool {
        self.get(key).is_some()
    }

    fn get(&self, key: &Canon) -> Option<Canon> {
        let hash = Self::hash_key(key);
        Self::lookup(&self.root, hash, key, 0)
    }

    fn put(&self, key: Canon, value: Canon) -> HamtMap {
        let hash = Self::hash_key(&key);
        let (next_root, inserted) = Self::insert(&self.root, hash, key, value, 0);
        if Rc::ptr_eq(&next_root, &self.root) {
            self.clone()
        } else {
            HamtMap {
                root: next_root,
                size: if inserted { self.size + 1 } else { self.size },
            }
        }
    }

    fn delete(&self, key: &Canon) -> HamtMap {
        let hash = Self::hash_key(key);
        let (next_root, removed) = Self::remove(&self.root, hash, key, 0);
        if !removed {
            self.clone()
        } else {
            HamtMap {
                root: next_root.unwrap_or_else(|| Rc::new(HamtNode::Empty)),
                size: self.size - 1,
            }
        }
    }

    fn canonical_entries(&self) -> Vec<(Canon, Canon)> {
        let mut entries = Vec::new();
        Self::collect(&self.root, &mut entries);
        entries.sort_by(|a, b| compare(&a.0, &b.0));
        entries
    }

    fn from_entries(entries: &[(Canon, Canon)]) -> HamtMap {
        let mut map = HamtMap::empty();
        for (key, value) in entries.iter() {
            map = map.put(key.clone(), value.clone());
        }
        map
    }

    fn from_entries_first_wins(entries: &[(Canon, Canon)]) -> HamtMap {
        let mut map = HamtMap::empty();
        for (key, value) in entries.iter() {
            if !map.contains(key) {
                map = map.put(key.clone(), value.clone());
            }
        }
        map
    }

    fn lookup(node: &Rc<HamtNode>, hash: u64, key: &Canon, shift: u32) -> Option<Canon> {
        match node.as_ref() {
            HamtNode::Empty => None,
            HamtNode::Leaf { key: leaf_key, value, .. } => {
                if leaf_key == key {
                    Some(value.clone())
                } else {
                    None
                }
            }
            HamtNode::Collision { entries, .. } => {
                entries.iter().find(|(k, _)| k == key).map(|(_, v)| v.clone())
            }
            HamtNode::BitmapIndexed { bitmap, children } => {
                let bit = Self::bitpos(Self::mask(hash, shift));
                if (bitmap & bit) == 0 {
                    None
                } else {
                    let idx = Self::index(*bitmap, bit);
                    Self::lookup(&children[idx], hash, key, shift + Self::BITS_PER_LEVEL)
                }
            }
        }
    }

    fn insert(node: &Rc<HamtNode>, hash: u64, key: Canon, value: Canon, shift: u32) -> (Rc<HamtNode>, bool) {
        match node.as_ref() {
            HamtNode::Empty => (
                Rc::new(HamtNode::Leaf { hash, key, value }),
                true,
            ),
            HamtNode::Leaf {
                hash: leaf_hash,
                key: leaf_key,
                value: leaf_value,
            } => {
                if *leaf_key == key {
                    if *leaf_value == value {
                        (node.clone(), false)
                    } else {
                        (
                            Rc::new(HamtNode::Leaf {
                                hash: *leaf_hash,
                                key: leaf_key.clone(),
                                value,
                            }),
                            false,
                        )
                    }
                } else if *leaf_hash == hash {
                    (
                        Rc::new(HamtNode::Collision {
                            hash,
                            entries: vec![(leaf_key.clone(), leaf_value.clone()), (key, value)],
                        }),
                        true,
                    )
                } else {
                    (
                        Self::merge_different_hashes(
                            *leaf_hash,
                            node.clone(),
                            hash,
                            Rc::new(HamtNode::Leaf { hash, key, value }),
                            shift,
                        ),
                        true,
                    )
                }
            }
            HamtNode::Collision {
                hash: collision_hash,
                entries,
            } => {
                if *collision_hash == hash {
                    if let Some(idx) = entries.iter().position(|(k, _)| *k == key) {
                        if entries[idx].1 == value {
                            (node.clone(), false)
                        } else {
                            let mut next = entries.clone();
                            next[idx] = (key, value);
                            (
                                Rc::new(HamtNode::Collision {
                                    hash,
                                    entries: next,
                                }),
                                false,
                            )
                        }
                    } else {
                        let mut next = entries.clone();
                        next.push((key, value));
                        (
                            Rc::new(HamtNode::Collision {
                                hash,
                                entries: next,
                            }),
                            true,
                        )
                    }
                } else {
                    (
                        Self::merge_different_hashes(
                            *collision_hash,
                            node.clone(),
                            hash,
                            Rc::new(HamtNode::Leaf { hash, key, value }),
                            shift,
                        ),
                        true,
                    )
                }
            }
            HamtNode::BitmapIndexed { bitmap, children } => {
                let bit = Self::bitpos(Self::mask(hash, shift));
                let idx = Self::index(*bitmap, bit);
                if (bitmap & bit) == 0 {
                    let mut next_children = children.clone();
                    next_children.insert(idx, Rc::new(HamtNode::Leaf { hash, key, value }));
                    (
                        Rc::new(HamtNode::BitmapIndexed {
                            bitmap: bitmap | bit,
                            children: next_children,
                        }),
                        true,
                    )
                } else {
                    let child = &children[idx];
                    let (next_child, inserted) =
                        Self::insert(child, hash, key, value, shift + Self::BITS_PER_LEVEL);
                    if Rc::ptr_eq(&next_child, child) {
                        (node.clone(), inserted)
                    } else {
                        let mut next_children = children.clone();
                        next_children[idx] = next_child;
                        (
                            Rc::new(HamtNode::BitmapIndexed {
                                bitmap: *bitmap,
                                children: next_children,
                            }),
                            inserted,
                        )
                    }
                }
            }
        }
    }

    fn remove(node: &Rc<HamtNode>, hash: u64, key: &Canon, shift: u32) -> (Option<Rc<HamtNode>>, bool) {
        match node.as_ref() {
            HamtNode::Empty => (Some(node.clone()), false),
            HamtNode::Leaf { key: leaf_key, .. } => {
                if leaf_key == key {
                    (None, true)
                } else {
                    (Some(node.clone()), false)
                }
            }
            HamtNode::Collision {
                hash: collision_hash,
                entries,
            } => {
                if *collision_hash != hash {
                    (Some(node.clone()), false)
                } else if let Some(idx) = entries.iter().position(|(k, _)| k == key) {
                    let mut next = entries.clone();
                    next.remove(idx);
                    match next.len() {
                        0 => (None, true),
                        1 => {
                            let (only_key, only_value) = next[0].clone();
                            (
                                Some(Rc::new(HamtNode::Leaf {
                                    hash,
                                    key: only_key,
                                    value: only_value,
                                })),
                                true,
                            )
                        }
                        _ => (
                            Some(Rc::new(HamtNode::Collision {
                                hash,
                                entries: next,
                            })),
                            true,
                        ),
                    }
                } else {
                    (Some(node.clone()), false)
                }
            }
            HamtNode::BitmapIndexed { bitmap, children } => {
                let bit = Self::bitpos(Self::mask(hash, shift));
                if (bitmap & bit) == 0 {
                    (Some(node.clone()), false)
                } else {
                    let idx = Self::index(*bitmap, bit);
                    let child = &children[idx];
                    let (next_child, removed) = Self::remove(child, hash, key, shift + Self::BITS_PER_LEVEL);
                    if !removed {
                        (Some(node.clone()), false)
                    } else if let Some(next_child_rc) = next_child {
                        if Rc::ptr_eq(&next_child_rc, child) {
                            (Some(node.clone()), true)
                        } else {
                            let mut next_children = children.clone();
                            next_children[idx] = next_child_rc;
                            (
                                Some(Rc::new(HamtNode::BitmapIndexed {
                                    bitmap: *bitmap,
                                    children: next_children,
                                })),
                                true,
                            )
                        }
                    } else {
                        let mut next_children = children.clone();
                        next_children.remove(idx);
                        let next_bitmap = bitmap & !bit;
                        if next_children.is_empty() {
                            (None, true)
                        } else if next_children.len() == 1 {
                            (Some(next_children[0].clone()), true)
                        } else {
                            (
                                Some(Rc::new(HamtNode::BitmapIndexed {
                                    bitmap: next_bitmap,
                                    children: next_children,
                                })),
                                true,
                            )
                        }
                    }
                }
            }
        }
    }

    fn merge_different_hashes(
        hash_a: u64,
        node_a: Rc<HamtNode>,
        hash_b: u64,
        node_b: Rc<HamtNode>,
        shift: u32,
    ) -> Rc<HamtNode> {
        if shift >= Self::MAX_SHIFT {
            let mut entries = Vec::new();
            Self::collect(&node_a, &mut entries);
            Self::collect(&node_b, &mut entries);
            Rc::new(HamtNode::Collision { hash: hash_a, entries })
        } else {
            let mask_a = Self::mask(hash_a, shift);
            let mask_b = Self::mask(hash_b, shift);
            let bit_a = Self::bitpos(mask_a);
            let bit_b = Self::bitpos(mask_b);
            if mask_a != mask_b {
                let children = if mask_a < mask_b {
                    vec![node_a, node_b]
                } else {
                    vec![node_b, node_a]
                };
                Rc::new(HamtNode::BitmapIndexed {
                    bitmap: bit_a | bit_b,
                    children,
                })
            } else {
                Rc::new(HamtNode::BitmapIndexed {
                    bitmap: bit_a,
                    children: vec![Self::merge_different_hashes(
                        hash_a,
                        node_a,
                        hash_b,
                        node_b,
                        shift + Self::BITS_PER_LEVEL,
                    )],
                })
            }
        }
    }

    fn collect(node: &Rc<HamtNode>, out: &mut Vec<(Canon, Canon)>) {
        match node.as_ref() {
            HamtNode::Empty => {}
            HamtNode::Leaf { key, value, .. } => out.push((key.clone(), value.clone())),
            HamtNode::Collision { entries, .. } => out.extend(entries.iter().cloned()),
            HamtNode::BitmapIndexed { children, .. } => {
                for child in children.iter() {
                    Self::collect(child, out);
                }
            }
        }
    }
}

#[derive(Clone)]
struct MapCacheEntry {
    weak_entries: Weak<Vec<(Canon, Canon)>>,
    map: Rc<HamtMap>,
}

thread_local! {
    static MAP_CACHE: RefCell<HashMap<usize, MapCacheEntry>> = RefCell::new(HashMap::new());
}

fn map_cache_key(entries: &Rc<Vec<(Canon, Canon)>>) -> usize {
    Rc::as_ptr(entries) as usize
}

fn map_of(value: &Canon) -> Result<Rc<HamtMap>, Fail> {
    match value {
        Canon::Map(entries) => {
            let key = map_cache_key(entries);
            if let Some(found) = MAP_CACHE.with(|cache| {
                let mut cache = cache.borrow_mut();
                match cache.get(&key) {
                    Some(entry) => match entry.weak_entries.upgrade() {
                        Some(alive) if Rc::ptr_eq(&alive, entries) => Some(entry.map.clone()),
                        _ => {
                            cache.remove(&key);
                            None
                        }
                    },
                    None => None,
                }
            }) {
                return Ok(found);
            }

            let built = Rc::new(HamtMap::from_entries(entries.as_ref()));
            MAP_CACHE.with(|cache| {
                cache.borrow_mut().insert(
                    key,
                    MapCacheEntry {
                        weak_entries: Rc::downgrade(entries),
                        map: built.clone(),
                    },
                );
            });
            Ok(built)
        }
        other => Err(Fail {
            kind: "type-error".to_string(),
            message: format!("expected a map, found {}", write_text(other)),
        }),
    }
}

fn canon_map_from_hamt(map: Rc<HamtMap>) -> Canon {
    let canon = Canon::Map(Rc::new(map.canonical_entries()));
    if let Canon::Map(entries) = &canon {
        let key = map_cache_key(entries);
        MAP_CACHE.with(|cache| {
            cache.borrow_mut().insert(
                key,
                MapCacheEntry {
                    weak_entries: Rc::downgrade(entries),
                    map,
                },
            );
        });
    }
    canon
}

fn string_of(value: &Canon) -> Result<&str, Fail> {
    match value {
        Canon::Str(text) => Ok(&**text),
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
                Canon::Node(_, values) => Ok(Canon::List(List::shared(values.clone()))),
                other => fail(
                    "type-error",
                    format!("args expects a node, found {}", write_text(other)),
                ),
            }
        }
        "node" => {
            arity(2)?;
            match &args[0] {
                Canon::Sym(tag) => Ok(Canon::Node(tag.clone(), Rc::new(list_of(&args[1])?.to_vec()))),
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
            Ok(Canon::list(items))
        }
        "snoc" => {
            arity(2)?;
            let mut items = list_of(&args[0])?.to_vec();
            items.push(args[1].clone());
            Ok(Canon::list(items))
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
                Ok(Canon::List(items.tail()))
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
            let mut items = list_of(&args[0])?.to_vec();
            items.extend(list_of(&args[1])?.iter().cloned());
            Ok(Canon::list(items))
        }
        "rev" => {
            arity(1)?;
            let mut items = list_of(&args[0])?.to_vec();
            items.reverse();
            Ok(Canon::list(items))
        }
        "take" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let count = index_of(&args[1])?.max(0) as usize;
            Ok(Canon::list(items.iter().take(count).cloned().collect()))
        }
        "drop" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let count = index_of(&args[1])?.max(0) as usize;
            Ok(Canon::list(items.iter().skip(count).cloned().collect()))
        }
        "sort" => {
            arity(1)?;
            let mut items = list_of(&args[0])?.to_vec();
            items.sort_by(compare);
            Ok(Canon::list(items))
        }
        "distinct" => {
            arity(1)?;
            let mut items: Vec<Canon> = Vec::new();
            for item in list_of(&args[0])?.iter() {
                if !items.contains(item) {
                    items.push(item.clone());
                }
            }
            Ok(Canon::list(items))
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
                let mut updated = items.to_vec();
                updated[index as usize] = args[2].clone();
                Ok(Canon::list(updated))
            }
        }
        "insert-at" => {
            arity(3)?;
            let items = list_of(&args[0])?;
            let index = index_of(&args[1])?;
            if index < 0 || index > items.len() as i128 {
                fail("index-out-of-range", format!("index {} out of range", index))
            } else {
                let mut updated = items.to_vec();
                updated.insert(index as usize, args[2].clone());
                Ok(Canon::list(updated))
            }
        }
        "remove-at" => {
            arity(2)?;
            let items = list_of(&args[0])?;
            let index = index_of(&args[1])?;
            if index < 0 || index >= items.len() as i128 {
                fail("index-out-of-range", format!("index {} out of range", index))
            } else {
                let mut updated = items.to_vec();
                updated.remove(index as usize);
                Ok(Canon::list(updated))
            }
        }
        "mnew" => {
            arity(0)?;
            Ok(canon_map_from_hamt(Rc::new(HamtMap::empty())))
        }
        "msize" => {
            arity(1)?;
            Ok(Canon::nat(map_of(&args[0])?.size as u128))
        }
        "mhas" => {
            arity(2)?;
            Ok(Canon::Bool(map_of(&args[0])?.contains(&args[1])))
        }
        "mget" => {
            arity(3)?;
            Ok(map_of(&args[0])?.get(&args[1]).unwrap_or_else(|| args[2].clone()))
        }
        "mput" => {
            arity(3)?;
            let updated = map_of(&args[0])?.put(args[1].clone(), args[2].clone());
            Ok(canon_map_from_hamt(Rc::new(updated)))
        }
        "mdel" => {
            arity(2)?;
            let updated = map_of(&args[0])?.delete(&args[1]);
            Ok(canon_map_from_hamt(Rc::new(updated)))
        }
        "mkeys" => {
            arity(1)?;
            Ok(Canon::list(map_of(&args[0])?.canonical_entries().iter().map(|(key, _)| key.clone()).collect()))
        }
        "mvals" => {
            arity(1)?;
            Ok(Canon::list(map_of(&args[0])?.canonical_entries().iter().map(|(_, value)| value.clone()).collect()))
        }
        "mentries" => {
            arity(1)?;
            let canonical_entries = map_of(&args[0])?.canonical_entries();
            Ok(Canon::list(
                canonical_entries
                    .iter()
                    .map(|(key, value)| Canon::node("entry", vec![key.clone(), value.clone()]))
                    .collect(),
            ))
        }
        "mfrom" => {
            arity(1)?;
            let mut entries = Vec::new();
            for item in list_of(&args[0])?.iter() {
                match item {
                    Canon::Node(tag, values) if &**tag == "entry" && values.len() == 2 => {
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
            let map = HamtMap::from_entries_first_wins(&entries);
            Ok(canon_map_from_hamt(Rc::new(map)))
        }
        "scat" => {
            arity(2)?;
            Ok(Canon::string(format!("{}{}", string_of(&args[0])?, string_of(&args[1])?)))
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
                Ok(Canon::string(text[from as usize..to as usize].iter().collect()))
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
            Ok(Canon::Sym(Rc::from(string_of(&args[0])?)))
        }
        "nat->str" => {
            arity(1)?;
            match &args[0] {
                Canon::Nat(value) => Ok(Canon::Str(Rc::from(value.to_decimal()))),
                Canon::Int(value) => Ok(Canon::Str(Rc::from(value.to_decimal()))),
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
            Ok(Canon::Bytes(Rc::new(bytes)))
        }
        "digest" => {
            arity(1)?;
            Ok(Canon::Ref(digest_of(&args[0])))
        }
        "encode" => {
            arity(1)?;
            Ok(Canon::Bytes(Rc::new(encode(&args[0]))))
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
                Canon::Ref(digest) => Ok(Canon::Str(Rc::from(digest.hex()))),
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

#[cfg(test)]
mod tests {
    use super::*;

    fn prim(name: &str, args: &[Canon]) -> Canon {
        primitive(name, args).expect("primitive call should succeed")
    }

    fn nat(value: u128) -> Canon {
        Canon::nat(value)
    }

    #[test]
    fn map_primitives_enumerate_canonically() {
        let m0 = prim("mnew", &[]);
        let m1 = prim("mput", &[m0.clone(), Canon::text("beta"), nat(2)]);
        let m2 = prim("mput", &[m1, Canon::text("alpha"), nat(1)]);
        let m3 = prim("mput", &[m2, Canon::text("gamma"), nat(3)]);

        assert_eq!(
            prim("mkeys", &[m3.clone()]),
            Canon::list(vec![Canon::text("alpha"), Canon::text("beta"), Canon::text("gamma")])
        );
        assert_eq!(
            prim("mvals", &[m3]),
            Canon::list(vec![nat(1), nat(2), nat(3)])
        );
    }

    #[test]
    fn mfrom_keeps_first_duplicate_value() {
        let entries = Canon::list(vec![
            Canon::node("entry", vec![Canon::text("alpha"), nat(1)]),
            Canon::node("entry", vec![Canon::text("alpha"), nat(9)]),
            Canon::node("entry", vec![Canon::text("beta"), nat(2)]),
        ]);

        let m = prim("mfrom", &[entries]);
        assert_eq!(prim("mget", &[m.clone(), Canon::text("alpha"), nat(0)]), nat(1));
        assert_eq!(prim("mget", &[m, Canon::text("beta"), nat(0)]), nat(2));
    }

    #[test]
    fn map_updates_are_persistent_across_versions() {
        let m0 = prim("mnew", &[]);
        let m1 = prim("mput", &[m0, Canon::text("alpha"), nat(1)]);
        let m2 = prim("mput", &[m1.clone(), Canon::text("beta"), nat(2)]);
        let m3 = prim("mput", &[m2.clone(), Canon::text("alpha"), nat(7)]);

        assert_eq!(prim("mkeys", &[m1]), Canon::list(vec![Canon::text("alpha")]));
        assert_eq!(
            prim("mkeys", &[m2.clone()]),
            Canon::list(vec![Canon::text("alpha"), Canon::text("beta")])
        );
        assert_eq!(prim("mget", &[m2, Canon::text("alpha"), nat(0)]), nat(1));
        assert_eq!(prim("mget", &[m3, Canon::text("alpha"), nat(0)]), nat(7));
    }

    #[test]
    fn mfrom_and_mentries_round_trip() {
        let m0 = prim("mnew", &[]);
        let m1 = prim("mput", &[m0, Canon::text("gamma"), nat(3)]);
        let m2 = prim("mput", &[m1, Canon::text("alpha"), nat(1)]);
        let m3 = prim("mput", &[m2, Canon::text("beta"), nat(2)]);

        let round_tripped = prim("mfrom", &[prim("mentries", &[m3.clone()])]);
        assert_eq!(round_tripped, m3);
    }
}

