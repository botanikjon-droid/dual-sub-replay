# SHOKALON — ultratovush ta’lim tizimi: loyiha fayllari

Bu papka SHOKALON loyihasining ilovadan tashqari materiallarini saqlaydi. Tuzilma loyiha promptidagi tavsiyaga mos:

| Papka | Mazmuni | Holat |
| --- | --- | --- |
| `02_VIDEO_CATALOG/SHOKALON_katalog_v13.xlsx` | 33 bob, 253 kichik mavzu (`BB.CC.TT` kodlari), 481 video (`V001…`), 4-bosqich auditi (havolalar qayta tekshiruvi, manba darajasi A/B/C), qidiruv navbati (inglizcha kalit so‘zlar), boshqa tildagi zaxira, qamrov, audit, rad etilgan havolalar | 2026-10-17 |
| `03_SUBTITLES_ORIGINAL/` (5-bosqich) | Ilovadagi "SHOKALON: subtitrlarni yig‘ish" kartochkasi telefonda yig‘adi va ZIP qiladi | Ilova tayyor; telefonda yig‘ish kutilmoqda |
| 6-bosqich: ZIP tekshiruvi | `tools/shokalon/check_subtitle_zip.py ZIP` — katalog bilan solishtiradi: yo‘q, takroriy, nomi yoki kodi mos kelmagan, buzilgan SRT | Vosita tayyor |
| `TARJIMA_STANDARTI.md` (7-bosqich) | Claude va ChatGPT uchun yagona tibbiy tarjima standarti; `prompts/tarjima_prompti.txt` — ChatGPT’ga beriladigan qisqa shakli | Loyiha; 4 ta ochiq qaror shifokorda |
| 8-bosqich: tarjima tekshiruvi | `tools/shokalon/check_translation.py ASL.srt TARJIMA.srt` yoki `--dirs` — bloklar va vaqt kodlari, sonlar, birliklar, inglizcha qoldiqlar, lug‘at, o‘/g‘, qator uzunligi | Vosita tayyor |
| `04_SUBTITLES_TRANSLATED/` … `10_RELEASES/` | Keyingi bosqichlar | Subtitrlar yig‘ilgach |

- Ilovadagi mundarija (`app/src/main/assets/uzi_catalog.tsv`) — katalogning ilovadagi ko‘rinishi. Ilovadagi har bir dars
  katalogda Video ID va mundarija kodi bilan bor ("Izoh" ustunida ilovadagi joyi).
- Video ID va mavzu kodlari hech qachon o‘zgartirilmaydi; ishlamay qolgan video qatori "Ishlamaydi" deb saqlanadi.
- Fayl nomi qoidasi: `SHK_{kod nuqtasiz}_{Video ID}_{ORIGINAL|ASR-EN|UZ-LATIN[-vN]}.srt`.
