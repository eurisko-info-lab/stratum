//! Artifact envelopes, the content addressed store and closure traversal.

use crate::canon::{decode, encode, Canon, Digest};
use crate::sha::sha256;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct Artifact {
    pub kind: String,
    pub body: Canon,
}

impl Artifact {
    pub fn to_canon(&self) -> Canon {
        Canon::Node(
            "artifact".to_string(),
            vec![Canon::Sym(self.kind.clone()), self.body.clone()],
        )
    }

    pub fn digest(&self) -> Digest {
        Digest(sha256(&encode(&self.to_canon())))
    }

    pub fn from_canon(value: Canon) -> Result<Artifact, String> {
        match value {
            Canon::Node(tag, mut args) if tag == "artifact" && args.len() == 2 => {
                let body = args.pop().unwrap();
                match args.pop().unwrap() {
                    Canon::Sym(kind) => Ok(Artifact { kind, body }),
                    _ => Err("artifact kind is not a symbol".to_string()),
                }
            }
            _ => Err("not an artifact envelope".to_string()),
        }
    }
}

pub fn digest_of(value: &Canon) -> Digest {
    Digest(sha256(&encode(value)))
}

/// A directory of `<digest>.canon` files, cached in memory.
pub struct Cas {
    root: PathBuf,
    cache: std::cell::RefCell<BTreeMap<Digest, Artifact>>,
}

impl Cas {
    pub fn open(root: &Path) -> Cas {
        Cas { root: root.to_path_buf(), cache: std::cell::RefCell::new(BTreeMap::new()) }
    }

    pub fn get(&self, digest: &Digest) -> Result<Artifact, String> {
        if let Some(artifact) = self.cache.borrow().get(digest) {
            return Ok(artifact.clone());
        }
        let path = self.root.join(format!("{}.canon", digest.hex()));
        let bytes = fs::read(&path).map_err(|_| format!("missing artifact {}", digest.hex()))?;
        if sha256(&bytes) != digest.0 {
            return Err(format!("artifact {} does not match its digest", digest.hex()));
        }
        let artifact = Artifact::from_canon(decode(&bytes)?)?;
        self.cache.borrow_mut().insert(*digest, artifact.clone());
        Ok(artifact)
    }

    pub fn has(&self, digest: &Digest) -> bool {
        self.get(digest).is_ok()
    }
}

/// Every digest reachable from `root`, or the first missing digest.
pub fn traverse(cas: &Cas, root: &Digest) -> Result<Vec<Digest>, String> {
    let mut seen: BTreeSet<Digest> = BTreeSet::new();
    let mut stack = vec![*root];
    while let Some(digest) = stack.pop() {
        if seen.contains(&digest) {
            continue;
        }
        let artifact = cas.get(&digest)?;
        seen.insert(digest);
        let mut refs = Vec::new();
        artifact.body.refs(&mut refs);
        for reference in refs {
            if !seen.contains(&reference) {
                stack.push(reference);
            }
        }
    }
    Ok(seen.into_iter().collect())
}

pub fn kind_counts(cas: &Cas, digests: &[Digest]) -> Result<BTreeMap<String, u64>, String> {
    let mut counts: BTreeMap<String, u64> = BTreeMap::new();
    for digest in digests {
        let artifact = cas.get(digest)?;
        *counts.entry(artifact.kind).or_insert(0) += 1;
    }
    Ok(counts)
}
