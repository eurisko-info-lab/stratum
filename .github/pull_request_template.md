<!--
The code here is written by AI agents, so a pull request is the unusual case
rather than the usual one. If you have not already opened an issue describing
the change, please do that first — CONTRIBUTING.md explains why, and it is not
a formality: the description is the part that outlives the patch.
-->

**What should become true, and how anyone would know it had**

<!-- The outcome, and the check that fails today and passes now. -->

**Issue**

<!-- Closes #… -->

**Gates**

All of these are green locally:

- [ ] `./tools/regenerate.sh` — every `.meta` and `.grammar` source re-elaborated
- [ ] `./tools/staircase.sh` — twelve layers rebuilt, no digest drift
- [ ] `./tools/deployment.sh` — every world rebuilt and constructed from the platform
- [ ] `sbt test` — canon properties, native boundary, every transcript
- [ ] `./tools/parity.sh` — both hosts agree digest for digest
- [ ] `./tools/cleanroom.sh` — rebuilt from executable, digest and closure alone

**Boundaries**

- [ ] The frozen host core gained no primitive, form or tag
- [ ] The host learned no vocabulary of the system above it
- [ ] The platform still names no application and no editor
- [ ] Generated artefacts are committed, and a world that changed had its
      canonical change re-derived

<!--
If a box is unchecked, say so rather than removing it. A change that needs a
boundary relaxed is not disqualified — it is a change that needs discussing.
-->
