# SHOKALON: yagona tibbiy tarjima standarti (7–8-bosqichlar)

Bu standart SHOKALON videodarslari subtitrlarini o‘zbek tiliga tarjima qiladigan har bir ijrochi uchun bitta: Claude,
ChatGPT yoki odam. U loyiha promptining 8-bandi, shifokorning `medical-uzbek-translator` ko‘nikmasi va ilovadagi
tasdiqlangan UZI lug‘ati (`app/src/main/assets/uzi_glossary.tsv`) asosida tuzilgan. Shifokor qarorlari 2026-10-18 da
kiritildi (9-bo‘lim).

**Holat:** loyiha (2026-10-17). Tarjima subtitrlar to‘liq yig‘ilgandan keyin boshlanadi. Faqat texnik jarayonni sinash
uchun pilot tarjima shifokorning roziligi bilan qilinishi mumkin.

## 1. Fayllar va nomlar

| Narsa | Qoida |
| --- | --- |
| Asl fayl | `03_SUBTITLES_ORIGINAL/SHK_{kod}_{Video ID}_ORIGINAL.srt` (muallif subtitri) yoki `..._ASR-EN.srt` (YouTube avtomatik subtitri). Hech qachon o‘zgartirilmaydi |
| Tarjima | `04_SUBTITLES_TRANSLATED/SHK_{kod}_{Video ID}_UZ-LATIN.srt`. Qayta tarjima: `..._UZ-LATIN-V2.srt`, `-V3` … |
| Hisobot | `05_SUBTITLE_QA/SHK_{kod}_{Video ID}_UZ-LATIN.md` va avtomatik tekshiruv natijasi |
| Kod va Video ID | Asl fayldagidek. Tarjimada ularni o‘zgartirish taqiqlanadi, aks holda tarjima videoga bog‘lanmaydi |
| Kodlash | UTF-8, Windows qator oxiri ham, Unix ham bo‘ladi |

Ko‘nikmadagi `asl nom + _uz` nomlash SHOKALON fayllariga qo‘llanmaydi: bu yerda yuqoridagi nom ishlatiladi.

## 2. Asosiy qoidalar

1. Inglizcha tibbiy ma’no aniq saqlanadi. Anatomik tuzilma, patologiya, son, o‘lchov birligi, tomon (o‘ng/chap,
   proksimal/distal, kranial/kaudal) o‘zgarmaydi.
2. Manbada yo‘q tashxis, tafsilot yoki izoh qo‘shilmaydi. Muallifning hazili va murojaatlari qisqa, lekin ma’nosi
   saqlangan holda beriladi.
3. Subtitr raqamlari, tartibi, soni va vaqt kodlari asl fayldagidek qoladi. Blok birlashtirilmaydi va bo‘linmaydi.
4. Gaplar butunligicha tarjima qilinadi: avval inglizcha gaplar bir necha blokdan tiklanadi, so‘ng o‘zbekcha gap
   (fe’l oxirida) yana o‘sha bloklarga ma’no bo‘yicha taqsimlanadi (ko‘nikmaning 1–4-bosqichlari).
5. Har bir blok o‘z vaqt oralig‘ida aytilayotgan mazmunga mos keladi. Blok boshida yoki oxirida ma’nosiz osilib
   qolgan so‘z (“bo‘lib,”, “bu”, “ikkita”) bo‘lmaydi; aniqlovchi aniqlanmishdan ajratilmaydi.
6. Bir xil tuzilma butun dars va butun kutubxona bo‘yicha bir xil nomlanadi.
7. Noaniq yoki noto‘g‘ri eshitilgan atama taxmin bilan tasdiqlangan fakt sifatida berilmaydi: eng ehtimoliy variant
   ishlatiladi va hisobotda “Past” ishonchlilik bilan barcha variantlar yoziladi.

## 3. Yozuv va imlo

| Masala | Qoida |
| --- | --- |
| Alifbo | Faqat o‘zbek lotin yozuvi. Kirill harfi bo‘lmaydi |
| o‘, g‘ | `‘` (U+2018) bilan: `o‘t`, `g‘ovak`. Butun faylda bitta shakl |
| Tutuq belgisi | `’` (U+2019) bilan: `ta’minlanish`, `a’zo` |
| Qisqartmalar | Lotin harflarida, asl ko‘rinishda: RI, PSV, CBD, SWE, TI-RADS, BI-RADS, O-RADS. Birinchi uchraganda lug‘atdagidek ochiladi: “rezistivlik indeksi (RI)” |
| Sonlar | Raqamlar manbadagidek. O‘nlik kasr vergul bilan: 3.5 → 3,5 (tekshiruvchi ikkalasini bir xil son deb hisoblaydi) |
| O‘lchov birliklari | Xalqaro belgilar o‘zgarmaydi: mm, cm, cm/s, kPa, MHz, mL, dB. Santimetr — “cm”, “sm” emas |
| Odam, kanal, apparat, dastur nomlari | Tarjima qilinmaydi: Sononerds, Voluson, Clarius, MedMastery |
| Qator uzunligi | Bir qatorda 42 belgigacha, blokda ko‘pi bilan 2 qator. Ortiqcha so‘zlar hisobiga qisqartiriladi; termin, son va tibbiy mazmun tushirilmaydi |

## 4. Lug‘at

Ustuvorlik tartibi:

1. **Ilovadagi lug‘at** `app/src/main/assets/uzi_glossary.tsv`: 268 ta atama, shifokor tekshirgan (`approved`,
   `corrected`, `new`). Majburiy. Inglizcha atama va uning sinonimlari (`aliases`) uchrasa, o‘zbekcha atama ishlatiladi.
   Qavs ichidagi qism (masalan, “datchik (zond)” dagi “zond”) birinchi uchraganda yozilishi mumkin, keyin asosiy shakl.
   `general = 1` belgili 20 ta umumiy so‘z (liver, kidney …) oddiy so‘z sifatida tarjima qilinadi.
2. **Lug‘atda umuman yo‘q atama**: o‘zbek tibbiy adabiyotida qabul qilingan shakl tanlanadi va “Yangi terminlar”ga
   yoziladi. Shifokor tasdiqlagandan keyingina lug‘atga kiradi (`docs/glossary/UZI_atamalari_tekshirilgan.xlsx` →
   `tools/glossary/convert_uzi_glossary.py`).

Grammatik qo‘shimchalar gapga moslashtiriladi (“datchikni”, “o‘t pufagidagi”), tibbiy ma’no emas.

Ko‘nikmadagi 24 ta atamaning 9 tasi lug‘atda bor edi; qolgan 16 tasi shifokor qarori bilan 2026-10-18 da lug‘atga
“Yangi” holatida qo‘shildi (229–244-yozuvlar). calcification — “kalsifikat” (ko‘nikmadagi “kalsinat / kalsifikatsiya”
o‘rniga). Lug‘atning 12 ta shubhali yozuvi (`docs/glossary/review-2026-10-10.md`) shifokor qarori bilan 2026-10-18 da
tuzatildi va 24 ta bog‘liq atama qo‘shildi (245–268-yozuvlar). Qoidalar:

- Umumiy atama aniqroq atamani bosib ketmaydi: “bile duct” — o‘t yo‘li, “common bile duct” — umumiy o‘t yo‘li
  (xoledox); “septum” — to‘siq, “interventricular septum” — qorinchalararo to‘siq. Ilova ham, tekshiruvchi ham eng uzun
  atamani oladi.
- Kasallik anatomik tuzilmaga aylanmaydi va aksincha: appendix — appendiks, appendicitis — appenditsit; lymph node —
  limfa tuguni, lymphadenopathy — limfadenopatiya.
- Manbada yo‘q joylashuv yoki belgi qo‘shilmaydi: “fontanelle” — liqildoq (“oldingi”, “katta” emas); “parenchyma” —
  parenxima (“miya” emas).
- Yozuv **exo-** asosida: anexogen, gipoexogen, izoexogen, giperexogen, exogen, exogenlik, exogen o‘choq, exotuzilma.
  “echogenic” — exogen (har doim giperexogen emas).
- benign — yaxshi sifatli, malignant — yomon sifatli (“xavfsiz / xavfli” emas).
- frequency — chastota; resolution — ajrata olish qobiliyati. Bu ikki tushuncha aralashtirilmaydi.

## 5. YouTube avtomatik subtitrlari (ASR-EN)

- Gap konteksti va tibbiy mazmun yetarlicha asoslasa, ASR xatosi tuzatiladi (“bankers head” → pancreas head,
  “hypo a coach” → hypoechoic). Tuzatish hisobotning “ASR tuzatishlari” bo‘limiga yoziladi.
- Asl audio tinglanmagan bo‘lsa, buni yozib qo‘yish shart: “ASR tuzatishlari faqat kontekst asosida”.
- Boshqa tildagi begona so‘zlar olib tashlanadi yoki kontekstdan tiklanadi.

## 6. Natija (har bir dars uchun)

1. `SHK_..._UZ-LATIN.srt` — UTF-8, bloklar soni va vaqt kodlari asl fayldagidek.
2. Avtomatik tekshiruv: `python3 tools/shokalon/check_translation.py ASL.srt TARJIMA.srt`. Xato bo‘lsa, fayl
   topshirilmaydi.
3. Hisobot — faqat o‘zgartirilgan, shubhali yoki tekshirish talab qiladigan bloklar uchun, uch bo‘lim:
   **ASR tuzatishlari**, **Terminologik tuzatishlar / yangi terminlar**, **Grammatik tahrirlar**. Har qatorda: blok
   raqami, asl matn, o‘zbekcha matn, variantlar, sabab, ishonchlilik (Yuqori / O‘rta / Past).
4. Tekshirib bo‘lmagan narsalar ochiq yoziladi. Tekshirilmagan narsani tekshirilgan deb yozish taqiqlanadi.

## 7. Avtomatik tekshiruv nimani ko‘radi

`tools/shokalon/check_translation.py` — 8-bosqich vositasi:

| Tekshiruv | Daraja |
| --- | --- |
| Bloklar soni, raqamlari va vaqt kodlari bir xil | Xato |
| Bo‘sh tarjima bloki, kirill harfi, UTF-8 emas | Xato |
| Asl blokdagi son tarjimada yo‘q | Xato |
| O‘lchov birligi (mm, cm, kPa, MHz …) tushib qolgan | Ogohlantirish |
| Inglizcha so‘zlar qolib ketgan (the, and, with …) | Ogohlantirish |
| Lug‘atdagi atama manbada bor, lekin tarjimada o‘zbekcha shakli yo‘q | Ogohlantirish |
| o‘/g‘ uchun noto‘g‘ri belgi (o', oʻ, o`) yoki aralash shakl | Ogohlantirish |
| Qator 42 belgidan uzun yoki blokda 2 tadan ortiq qator | Ogohlantirish |

Ogohlantirishlar tarjimani rad etmaydi, lekin har biri ko‘rib chiqiladi. Avtomatik tekshiruvdan o‘tish tibbiy
to‘g‘rilikni isbotlamaydi: yakuniy tibbiy tekshiruvni shifokor qiladi.

## 8. Ish tartibi

1. Subtitr ZIP’i keladi → `tools/shokalon/check_subtitle_zip.py` (6-bosqich) katalog bilan solishtiradi.
2. Tarjima bobma-bob, bir dars — bir topshiriq. Har bir topshiriqqa asl SRT, shu standart va lug‘at beriladi.
3. Har bir tarjima `check_translation.py` dan o‘tadi, xatolar tuzatiladi.
4. Shifokor hisobotdagi “Past” va “O‘rta” bloklarni va “Yangi terminlar”ni ko‘rib chiqadi. Tasdiqlangan atamalar lug‘atga
   qo‘shiladi.
5. Tasdiqlangan tarjimalar 9-bosqichda ilovaga tayyorlanadi.

## 9. Shifokor qarorlari

| Savol | Qaror (2026-10-18) |
| --- | --- |
| calcification | “kalsifikat” |
| Santimetr belgisi | “cm” |
| Ko‘nikmadagi ⏳ atamalar | Lug‘atga qo‘shildi (16 ta yangi, 9 tasi oldin bor edi) |
| Lug‘atning 12 ta shubhali yozuvi | Tuzatildi, 24 ta bog‘liq atama qo‘shildi (4-bo‘lim) |
