# SHOKALON — ultratovush ta’lim tizimi: loyiha fayllari

Bu papka SHOKALON loyihasining ilovadan tashqari materiallarini saqlaydi. Tuzilma loyiha promptidagi tavsiyaga mos:

| Papka | Mazmuni | Holat |
| --- | --- | --- |
| `02_VIDEO_CATALOG/SHOKALON_katalog_v4.xlsx` | 33 bob, 253 kichik mavzu (`BB.CC.TT` kodlari), 192 video (`V001…`), qidiruv navbati (inglizcha kalit so‘zlar), qamrov, audit, rad etilgan havolalar | 2026-10-17 |
| `03_SUBTITLES_ORIGINAL/` … `10_RELEASES/` | Keyingi bosqichlar | Hali boshlanmagan |

- Ilovadagi mundarija (`app/src/main/assets/uzi_catalog.tsv`) — katalogning ilovadagi ko‘rinishi. Ilovadagi har bir dars
  katalogda Video ID va mundarija kodi bilan bor ("Izoh" ustunida ilovadagi joyi).
- Video ID va mavzu kodlari hech qachon o‘zgartirilmaydi; ishlamay qolgan video qatori "Ishlamaydi" deb saqlanadi.
- Fayl nomi qoidasi: `SHK_{kod nuqtasiz}_{Video ID}_{ORIGINAL|ASR-EN|UZ-LATIN[-vN]}.srt`.
