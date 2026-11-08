# Scouter protocol library

scouterctl uses `scouter-common` to read Scouter's binary protocol.

| Item | Value |
| --- | --- |
| Artifact | `scouter-common-2.21.3.jar` |
| SHA-256 | `89aa7f9343843be682fec6c166f12f2a84636b7756680a4fb9972dd9a9ee9591` |
| Source repository | [scouter-project/scouter](https://github.com/scouter-project/scouter) |
| Source revision | `959f18d3055399c1adfbb2835f0892e0da344924` |
| Build module | `scouter.common` |
| License | [Apache License 2.0](SCOUTER-LICENSE.txt) |

This is a pinned artifact built from the source revision above. It is not guaranteed to be identical to an official release binary. Its SHA-256 checksum is verified during the build. Command packages include this library under `lib/`; source builds of the executable JAR include its classes.

The codec version does not require an identically versioned Collector; see [supported Collector versions](../docs/supported-versions.md).

When replacing the library, update its provenance and checksum together, and verify Collector compatibility.
