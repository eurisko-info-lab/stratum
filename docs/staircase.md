# Staircase

The first Stratum campaign climbs in three steps:

1. Cairn hosts `StratumApplicationV1` and reconstructs `F1`.
2. Stratum derives and accepts the first governed successor `delta1`.
3. Scala and Rust bootstrap hosts independently reconstruct `F2` from the
   retained canonical closure.

This scaffold establishes the repository boundary and artifact locations needed
for milestones S0 through S7 without introducing host-language semantic
authority into Stratum itself.
