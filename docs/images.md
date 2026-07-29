# Materialization images

A Git commit is a transition from one immutable materialization image to the
next. It is not a request to replay the repository from F0.

Let $I_n$ be the image associated with commit $n$, $\Delta_n$ the change made
by that commit, and $D_n$ its tracked semantic digest. Advancement is:

$$
I_n = \mathsf{verify}(\mathsf{apply}(I_{n-1}, \Delta_n), D_n)
$$

F0 is the only image without a parent. Every other commit requires the exact
image of its Git parent. A missing parent image is an availability failure; it
does not authorize rebuilding earlier commits.

## Representation

Images are data-only OCI images. `/stratum/objects` is a content-addressed
store keyed by SHA-256. `/stratum/index.sha256` maps disposable working-tree
paths to those objects. Each OCI image is built `FROM` its parent and adds only
objects not already present there, plus the current complete path index.

The index covers generated Grammar and Meta programs, closures, foundation
manifests, evidence, transition derivations and the frozen host manifest.
Tracked sources, expected digests, application artifacts and verdicts remain
in Git.

An image records the exact Git revision it materializes. Restore rejects an
image whose recorded revision differs from the requested commit, an object
whose bytes differ from its name, an absent indexed object, or an unsafe path.

## Advancement

`tools/advance-image.sh` performs one transition:

1. restore and verify the immediate parent image;
2. regenerate only declared outputs whose expected hash changed;
3. rebuild only worlds whose tracked semantic digest changed in this commit;
4. verify and reconstruct those worlds;
5. derive and verify each changed successor from its predecessor;
6. emit one child OCI layer.

CI looks for the exact commit image first. On a miss it finds the nearest
published ancestor and advances each unpublished commit once, in order. A
trusted push publishes those immutable commit images. Pull requests keep their
new layers local to the workflow.

Image compaction may copy an existing reachable CAS and index into a new
transport base when an OCI implementation approaches its layer limit. That is
byte-preserving storage maintenance, not semantic regeneration.

## Retention

Every image reachable from a live branch, tag or publication is retained.
Availability checks verify that parent links and objects remain fetchable.
They never substitute a clean rebuild for a missing layer.