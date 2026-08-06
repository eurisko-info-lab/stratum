//! An independent bootstrap host for Stratum.
//!
//! This implementation shares no code with the reference host. It decodes
//! canonical artifacts, recomputes identity, traverses closures, interprets
//! GrammarMachine0 and MetaMachine0, and emits canonical verdicts. For the same
//! foundation it must produce the same bytes as the reference host.

mod canon;
mod grammar;
mod host_core;
mod meta;
mod number;
mod regex;
mod sha;
mod store;

use std::rc::Rc;
use canon::{decode, read_text, write_text, Canon, Digest};
use meta::{Budget, Kernel, Program};
use std::env;
use std::fs;
use std::path::Path;
use store::{digest_of, kind_counts, traverse, Cas};

struct Foundation {
    digest: Digest,
    manifest: Canon,
    application_digest: Digest,
    meta_digest: Digest,
    program: Program,
    kernel: Kernel,
    budget: Budget,
    checks: Vec<Canon>,
}

fn read_digest(directory: &Path) -> Result<Digest, String> {
    let text = fs::read_to_string(directory.join("digest.txt"))
        .map_err(|_| "no digest.txt".to_string())?;
    Digest::from_hex(text.trim()).ok_or_else(|| "digest.txt is not a canonical digest".to_string())
}

/// Loads a foundation from `digest.txt` and `closure/` only.
fn load(directory: &Path, cas: &Cas) -> Result<Foundation, String> {
    let digest = read_digest(directory)?;
    let foundation = cas.get(&digest)?;
    if foundation.kind != "foundation" {
        return Err("root artifact is not a foundation".to_string());
    }
    let application_digest = match foundation.body.field("application") {
        Some(Canon::Ref(reference)) => *reference,
        _ => return Err("foundation has no application reference".to_string()),
    };
    let application = cas.get(&application_digest)?;
    if application.kind != "application" {
        return Err("application artifact has the wrong kind".to_string());
    }
    let meta_digest = match application.body.field("meta") {
        Some(Canon::Ref(reference)) => *reference,
        _ => return Err("application has no meta program reference".to_string()),
    };
    let meta_artifact = cas.get(&meta_digest)?;
    if meta_artifact.kind != "meta-program" {
        return Err("meta program artifact has the wrong kind".to_string());
    }
    let program = Program::load(&meta_artifact.body, cas)?;
    let kernel = application
        .body
        .field("kernel")
        .and_then(Kernel::from_canon)
        .unwrap_or_default();
    let budget = application
        .body
        .field("resources")
        .and_then(Budget::from_canon)
        .unwrap_or_else(Budget::default_budget);
    let checks = match application.body.field("checks") {
        Some(Canon::List(items)) => items.to_vec(),
        Some(Canon::Ref(reference)) => match cas.get(reference)?.body {
            Canon::List(items) => items.to_vec(),
            _ => Vec::new(),
        },
        _ => Vec::new(),
    };
    Ok(Foundation {
        digest,
        manifest: foundation.body,
        application_digest,
        meta_digest,
        program,
        kernel,
        budget,
        checks,
    })
}

fn attest(directory: &Path) -> Result<Canon, String> {
    let cas = Cas::open(&directory.join("closure"));
    let foundation = load(directory, &cas)?;
    let digests = traverse(&cas, &foundation.digest)?;
    let counts = kind_counts(&cas, &digests)?;
    let name = match foundation.manifest.field("name") {
        Some(Canon::Str(text)) => text.clone(),
        _ => return Err("foundation has no name".to_string()),
    };
    Ok(Canon::node(
        "attestation",
        vec![
            Canon::node("name", vec![Canon::Str(name)]),
            Canon::node("foundation", vec![Canon::Ref(foundation.digest)]),
            Canon::node("application", vec![Canon::Ref(foundation.application_digest)]),
            Canon::node("meta", vec![Canon::Ref(foundation.meta_digest)]),
            Canon::node("closure", vec![Canon::nat(digests.len() as u128)]),
            Canon::node(
                "kinds",
                vec![Canon::map(
                    counts
                        .into_iter()
                        .map(|(kind, count)| (Canon::Sym(Rc::from(kind)), Canon::nat(count as u128)))
                        .collect(),
                )],
            ),
            Canon::node("complete", vec![Canon::Bool(true)]),
        ],
    ))
}

/// Derives every declared check and records the digest of each canonical
/// verdict. Two hosts agree exactly when this report is byte identical.
fn derivation_report(directory: &Path) -> Result<Canon, String> {
    let cas = Cas::open(&directory.join("closure"));
    let foundation = load(directory, &cas)?;
    let seed = foundation.digest.hex();
    let mut reported = Vec::new();
    for check in &foundation.checks {
        let (name, goal, budget) = match check {
            Canon::Node(tag, args) if &**tag == "check" && args.len() >= 3 => {
                let name = args[0].as_sym().ok_or("check name must be a symbol")?.to_string();
                let budget = args
                    .get(3)
                    .and_then(Budget::from_canon)
                    .unwrap_or(foundation.budget);
                (name, args[1].clone(), budget)
            }
            other => return Err(format!("malformed check: {}", write_text(other))),
        };
        let verdict = meta::derive(
            &foundation.program,
            &cas,
            foundation.kernel.clone(),
            budget,
            &goal,
            &seed,
        );
        reported.push(Canon::node(
            "check",
            vec![Canon::Sym(Rc::from(name)), Canon::Ref(digest_of(&verdict))],
        ));
    }
    Ok(Canon::node(
        "derivation-report",
        vec![
            Canon::node("foundation", vec![Canon::Ref(foundation.digest)]),
            Canon::node("checks", vec![Canon::list(reported)]),
        ],
    ))
}

fn derive_goal(directory: &Path, goal_text: &str) -> Result<Canon, String> {
    let cas = Cas::open(&directory.join("closure"));
    let foundation = load(directory, &cas)?;
    let goal = canon::read_text(goal_text)?;
    Ok(meta::derive(
        &foundation.program,
        &cas,
        foundation.kernel.clone(),
        foundation.budget,
        &goal,
        &foundation.digest.hex(),
    ))
}

/// Decodes a file and reports whether it is canonical. Both hosts must accept
/// and reject exactly the same bytes.
fn canon_check(path: &Path) -> Canon {
    match fs::read(path) {
        Err(_) => Canon::node("canon", vec![Canon::sym("unreadable")]),
        Ok(bytes) => match decode(&bytes) {
            Ok(value) => Canon::node(
                "canon",
                vec![Canon::sym("accepted"), Canon::Ref(digest_of(&value))],
            ),
            Err(_) => Canon::node("canon", vec![Canon::sym("rejected")]),
        },
    }
}

/// Parses `text_path` under the grammar declared at `grammar_path`, using the
/// same generic GrammarMachine0 data interpreter the reference host uses, so
/// the two hosts can be compared file by file without either containing any
/// language-specific parsing code.
fn grammar_parse(grammar_path: &Path, text_path: &Path) -> Result<Canon, String> {
    let grammar_source = fs::read_to_string(grammar_path)
        .map_err(|error| format!("{}: {}", grammar_path.display(), error))?;
    let grammar_value = read_text(&grammar_source)?;
    let loaded = grammar::load(&grammar_value)?;
    let text = fs::read_to_string(text_path)
        .map_err(|error| format!("{}: {}", text_path.display(), error))?;
    grammar::parse(&loaded, &text)
}

/// Reads a keyed field out of a map. The verifier dispatches on no tag of the
/// system above it, exactly as the other host does not.
fn field<'a>(value: &'a Canon, key: &str) -> Option<&'a Canon> {
    match value {
        Canon::Map(entries) => entries
            .iter()
            .find(|(k, _)| matches!(k, Canon::Sym(name) if &**name == key))
            .map(|(_, v)| v),
        _ => None,
    }
}

fn items(value: Option<&Canon>) -> Vec<Canon> {
    match value {
        Some(Canon::List(items)) => items.to_vec(),
        _ => Vec::new(),
    }
}

/// Examines a run this host did not perform.
///
/// A run happens on whichever host holds the working tree, so the other one
/// cannot repeat it. What it can do is read the record and say whether the
/// tree now agrees with what the record claims was left behind. Together with
/// the answers the run wrote down, that is the part of a run two hosts can
/// agree about without both performing it.
fn examine_run(path: &Path) -> Result<Canon, String> {
    let text = fs::read_to_string(path).map_err(|_| format!("unreadable record {}", path.display()))?;
    let record = read_text(&text).map_err(|m| format!("record is not canonical: {}", m))?;

    let root = path.parent().unwrap_or(Path::new("."));
    let mut agreed = 0usize;
    let mut disagreed: Vec<Canon> = Vec::new();

    for entry in items(field(&record, "changed")) {
        let name = match field(&entry, "path") {
            Some(Canon::Str(text)) => text.clone(),
            _ => continue,
        };
        // Only what the run says it left behind can be checked now. A prior
        // state it replaced is gone, and a record that claimed otherwise would
        // be claiming something unfalsifiable.
        if let Some(Canon::Ref(expected)) = field(&entry, "after") {
            let found = fs::read(root.join(&*name)).ok().map(|bytes| Digest(sha::sha256(&bytes)));
            match found {
                Some(actual) if actual.hex() == expected.hex() => agreed += 1,
                _ => disagreed.push(Canon::Str(name)),
            }
        }
    }

    let outcome = field(&record, "outcome").cloned().unwrap_or(Canon::sym("unstated"));
    // Written in canonical key order, because a map that is not sorted is not
    // a canonical value and the other host would be right to reject it.
    Ok(Canon::map(vec![
        (Canon::sym("agreed"), Canon::nat(agreed as u128)),
        (Canon::sym("disagreed"), Canon::list(disagreed)),
        (Canon::sym("outcome"), outcome),
        (Canon::sym("read"), Canon::nat(items(field(&record, "read")).len() as u128)),
        (Canon::sym("record"), Canon::Ref(digest_of(&record))),
        (Canon::sym("steps"), Canon::nat(items(field(&record, "steps")).len() as u128)),
    ]))
}

fn option(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|argument| argument == name)
        .and_then(|index| args.get(index + 1))
        .cloned()
}

fn emit(label: &str, value: &Canon, out: Option<String>) {
    let lines = format!("{}\n{} {}\n", write_text(value), label, digest_of(value).hex());
    if let Some(path) = out {
        if let Some(parent) = Path::new(&path).parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::write(path, &lines);
    }
    print!("{}", lines);
}

/// A token-dense source file can elaborate into a fold tree one Canon::Node
/// deep per token, and printing or dropping that tree recurses one stack
/// frame per level. The default thread stack is not enough for every real
/// file this host must agree with the reference host about, so the actual
/// work runs on a thread sized for that, rather than reshaping the recursion.
fn main() {
    std::thread::Builder::new()
        .stack_size(256 * 1024 * 1024)
        .spawn(run)
        .expect("failed to spawn worker thread")
        .join()
        .expect("worker thread panicked");
}

fn run() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: stratum-verify <attest|report|derive|canon|host-manifest> [path] [options]");
        std::process::exit(2);
    }

    let out = option(&args, "--out");

    if args[1] == "host-manifest" {
        emit("host-core", &host_core::manifest(), out);
        return;
    }

    if args.len() < 3 {
        eprintln!("usage: stratum-verify <attest|report|derive|canon|run> <path> [options]");
        std::process::exit(2);
    }
    let path = args[2].clone();

    let result: Result<(), String> = match args[1].as_str() {
        "attest" => attest(Path::new(&path)).map(|value| emit("attestation", &value, out)),
        "report" => derivation_report(Path::new(&path)).map(|value| emit("report", &value, out)),
        "derive" => match option(&args, "--goal") {
            Some(goal) => derive_goal(Path::new(&path), &goal).map(|value| emit("verdict", &value, out)),
            None => Err("derive requires --goal <expression>".to_string()),
        },
        "canon" => {
            let value = canon_check(Path::new(&path));
            emit("canon", &value, out);
            Ok(())
        }
        "run" => examine_run(Path::new(&path)).map(|value| emit("run", &value, out)),
        "grammar-parse" => match option(&args, "--text-file") {
            Some(text_path) => grammar_parse(Path::new(&path), Path::new(&text_path)).map(|value| {
                println!("{}", write_text(&value));
            }),
            None => Err("grammar-parse requires --text-file <path>".to_string()),
        },
        other => Err(format!("unknown command {}", other)),
    };

    if let Err(message) = result {
        println!("error: {}", message);
        std::process::exit(1);
    }
}
