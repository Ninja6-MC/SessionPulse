# Third-Party Notices

SessionPulse is licensed under the GNU General Public License v3.0; see `LICENSE`.

The plugin jar also contains the third-party components listed below, shaded and relocated
under `com.ninja6.sessionpulse.lib`. Each is licensed under the MIT License, whose terms
require the copyright notice and permission notice to be included in all copies. They are
reproduced here, and this file is packaged into the jar as
`META-INF/THIRD_PARTY_NOTICES.md` alongside `META-INF/LICENSE`.

Versions are the resolved `runtimeClasspath` of this build
(`./gradlew dependencies --configuration runtimeClasspath`). `net.kyori:adventure-bom`
appears on that classpath as a platform constraint only and contributes no classes, so it
is not listed. A new shaded dependency needs an entry here; see `CONTRIBUTING.md`.

---

## Adventure

- Repository: https://github.com/PaperMC/adventure
- Copyright: Copyright (c) 2017-2025 KyoriPowered
- Licence: MIT License (text below)
- Components:
  - `net.kyori:adventure-api:4.26.1`
  - `net.kyori:adventure-key:4.26.1`
  - `net.kyori:adventure-nbt:4.26.1`
  - `net.kyori:adventure-text-minimessage:4.26.1`
  - `net.kyori:adventure-text-serializer-commons:4.26.1`
  - `net.kyori:adventure-text-serializer-gson:4.26.1`
  - `net.kyori:adventure-text-serializer-json:4.26.1`
  - `net.kyori:adventure-text-serializer-json-legacy-impl:4.26.1`
  - `net.kyori:adventure-text-serializer-legacy:4.26.1`
  - `net.kyori:adventure-text-serializer-gson-legacy-impl:4.21.0`

## Adventure Platform

- Repository: https://github.com/KyoriPowered/adventure-platform
- Copyright: Copyright (c) 2018-2020 KyoriPowered
- Licence: MIT License (text below)
- Components:
  - `net.kyori:adventure-platform-api:4.4.1`
  - `net.kyori:adventure-platform-bukkit:4.4.1`
  - `net.kyori:adventure-platform-facet:4.4.1`
  - `net.kyori:adventure-platform-viaversion:4.4.1`
  - `net.kyori:adventure-text-serializer-bungeecord:4.4.1`

## examination

- Repository: https://github.com/KyoriPowered/examination
- Copyright: Copyright (c) 2018-2019 KyoriPowered
- Licence: MIT License (text below)
- Components:
  - `net.kyori:examination-api:1.3.0`
  - `net.kyori:examination-string:1.3.0`

## option

- Repository: https://github.com/KyoriPowered/option
- Copyright: Copyright (c) 2023 KyoriPowered
- Licence: MIT License (text below)
- Components:
  - `net.kyori:option:1.1.0`

### MIT License (Adventure, Adventure Platform, examination, option)

The four Kyori repositories above publish the same MIT License text, differing only in the
copyright line. That line is given with each group above and stands at the head of this
text for that group.

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## FoliaLib

- Repository: https://github.com/TechnicallyCoded/FoliaLib
- Copyright: Copyright 2023 TechnicallyCoded
- Licence: MIT License (text below; FoliaLib's published POM declares no licence, so this
  is the `LICENSE` file at the repository's `0.5.1` tag)
- Components:
  - `com.tcoded:FoliaLib:0.5.1`

FoliaLib's text is reproduced separately because its wording is not byte-identical to the
Kyori text above.

```
Copyright 2023 TechnicallyCoded

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the “Software”), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```
