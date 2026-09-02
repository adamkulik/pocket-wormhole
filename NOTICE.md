# Notices & licenses

Pocket Wormhole is a native Android port of [Project Wormhole]
(https://gitlab.com/znixian/xftl), a clean-room reimplementation of the FTL
engine. This repository contains **no FTL game assets**. To play, you must
supply `ftl.dat` from your own legally-obtained copy of FTL: Faster Than
Light.

FTL: Faster Than Light is © Subset Games. This project is not affiliated
with, endorsed by, or connected to Subset Games in any way.

## This project

Copyright © the Pocket Wormhole contributors.
Licensed under the [GNU General Public License v2.0](LICENSE) or (at your
option) any later version, matching upstream Project Wormhole.

## Third-party components

| Component | Copyright | License |
|---|---|---|
| [Project Wormhole (xftl)](https://gitlab.com/znixian/xftl) | Campbell Suter (ZNix) and contributors | GPL-2.0 (or later, per upstream) |
| [Slipstream Mod Manager](https://github.com/Vhati/Slipstream-Mod-Manager) (partial, `net.vhati.*`) | Vhati and contributors | GPL-2.0 |
| [JOrbis / JOgg](http://www.jcraft.com/jorbis/) (`com.jcraft.*`, `libs/jorbis-core.jar`) | ymnk, JCraft,Inc. | LGPL-2.1 (modifications included with the source) |
| PNGDecoder (`org.newdawn.slick.opengl.PNGDecoder`) | 2008-2010 Matthias Mann | BSD-3-clause |
| [JDOM 2](https://github.com/hunterhacker/jdom) | JDOM authors | JDOM license (Apache-style) |
| [SLF4J](https://www.slf4j.org/) | QOS.ch | MIT |
| [Jackson](https://github.com/FasterXML/jackson) | FasterXML | Apache-2.0 |
| [JetBrains Annotations](https://github.com/JetBrains/java-annotations) | JetBrains | Apache-2.0 |
| Roboto font (baked for the ship editor) | Google | Apache-2.0 |
| Android, JDK | Google / OpenJDK contributors | Android SDK & OpenJDK licenses |

`libs/jorbis-core.jar` is JOrbis 0.0.17 repackaged (JOgg + JOrbis without the
engine's patched `VorbisFile`, which is compiled from source in-tree).

The Android port layer (`com.pocketwormhole.android.*`, `org.lwjgl.*` shims)
is original code written for this port, licensed under the same GPL-2.0
(or later) terms as the rest of the project.
