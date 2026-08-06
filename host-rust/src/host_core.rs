//! The frozen host core interface, as implemented by this host.
//!
//! Both hosts publish this manifest. If the two implementations ever diverge on
//! the canonical tags, the Meta0 forms, the primitive set, the Grammar0 forms,
//! the evidence shape or the verdict forms, the digests stop matching.

use std::rc::Rc;
use crate::canon::Canon;

fn symbols(names: &[&str]) -> Canon {
    Canon::list(names.iter().map(|name| Canon::sym(name)).collect())
}

const CANON_TAGS: [&str; 11] = [
    "unit", "bool", "nat", "int", "bytes", "string", "symbol", "reference", "list", "map", "node",
];

const EXPRESSION_FORMS: [&str; 12] = [
    "q", "v", "mk", "lst", "mp", "if", "let", "call", "match", "prim", "cap", "fail",
];

const PATTERN_FORMS: [&str; 8] = ["_", "pv", "pq", "pm", "pnode", "pl", "pcons", "pnil"];

/// Sorted, and identical to the reference host's primitive set.
const PRIMITIVES: [&str; 72] = [
    "add", "and", "append", "args", "bcat", "blen", "cmp", "cons", "contains", "decode", "digest",
    "distinct", "div", "drop", "encode", "eq", "ge", "gt", "head", "index-of", "insert-at",
    "is-bool", "is-bytes", "is-int", "is-list", "is-map", "is-nat", "is-node", "is-ref", "is-str",
    "is-sym", "is-unit", "le", "len", "lt", "max", "mdel", "mentries", "mfrom", "mget", "mhas",
    "min", "mkeys", "mnew", "mod", "mput", "msize", "mul", "mvals", "nat->str", "ne", "nil?",
    "node", "not", "nth", "or", "ref->str", "remove-at", "rev", "scat", "set-nth", "slen", "snoc",
    "sort", "ssub", "str->nat", "str->sym", "sub", "sym->str", "tag", "tail", "take",
];

const GRAMMAR_DECLARATIONS: [&str; 5] = ["name", "start", "token", "skip", "category"];
const GRAMMAR_PRODUCTIONS: [&str; 4] = ["prod", "pass", "fold", "paren"];
const GRAMMAR_ELEMENTS: [&str; 2] = ["kw", "bind"];
const TOKEN_KINDS: [&str; 3] = ["sym", "str", "nat"];
const EVIDENCE_FIELDS: [&str; 3] = ["steps", "calls", "capabilities"];
const VERDICT_FORMS: [&str; 2] = ["ok", "error"];

pub fn manifest() -> Canon {
    Canon::node(
        "host-core",
        vec![
            Canon::node("bootstrap", vec![Canon::Str(Rc::from("StratumHost0/1".to_string()))]),
            Canon::node(
                "canon",
                vec![
                    Canon::node("tags", vec![symbols(&CANON_TAGS)]),
                    Canon::node("identity", vec![Canon::sym("sha-256")]),
                ],
            ),
            Canon::node(
                "artifact",
                vec![Canon::node("envelope", vec![symbols(&["kind", "body"])])],
            ),
            Canon::node(
                "meta0",
                vec![
                    Canon::node("expressions", vec![symbols(&EXPRESSION_FORMS)]),
                    Canon::node("patterns", vec![symbols(&PATTERN_FORMS)]),
                    Canon::node("primitives", vec![symbols(&PRIMITIVES)]),
                ],
            ),
            Canon::node(
                "grammar0",
                vec![
                    Canon::node("declarations", vec![symbols(&GRAMMAR_DECLARATIONS)]),
                    Canon::node("productions", vec![symbols(&GRAMMAR_PRODUCTIONS)]),
                    Canon::node("elements", vec![symbols(&GRAMMAR_ELEMENTS)]),
                    Canon::node("token-kinds", vec![symbols(&TOKEN_KINDS)]),
                ],
            ),
            Canon::node("evidence", vec![Canon::node("fields", vec![symbols(&EVIDENCE_FIELDS)])]),
            Canon::node("verdict", vec![Canon::node("forms", vec![symbols(&VERDICT_FORMS)])]),
        ],
    )
}
