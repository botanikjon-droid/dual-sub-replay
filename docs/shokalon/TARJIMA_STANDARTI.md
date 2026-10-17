# SHOKALON: yagona tibbiy tarjima standarti (7–8-bosqichlar)

Bu standart SHOKALON videodarslari subtitrlarini o‘zbek tiliga tarjima qiladigan har bir ijrochi uchun bitta: Claude,
ChatGPT yoki odam. U loyiha promptining 8-bandi, shifokorning `medical-uzbek-translator` ko‘nikmasi va ilovadagi
tasdiqlangan UZI lug‘ati (`app/src/main/assets/uzi_glossary.tsv`) asosida tuzilgan.

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
| O‘lchov birliklari | Xalqaro belgilar o‘zgarmaydi: mm, cm, cm/s, kPa, MHz, mL, dB. (Ochiq qaror: “cm” o‘rniga “sm” yozilsinmi — 9-bo‘lim) |
| Odam, kanal, apparat, dastur nomlari | Tarjima qilinmaydi: Sononerds, Voluson, Clarius, MedMastery |
| Qator uzunligi | Bir qatorda 42 belgigacha, blokda ko‘pi bilan 2 qator. Ortiqcha so‘zlar hisobiga qisqartiriladi; termin, son va tibbiy mazmun tushirilmaydi |

## 4. Lug‘at

Ustuvorlik tartibi:

1. **Ilovadagi lug‘at** `app/src/main/assets/uzi_glossary.tsv`: 228 ta atama, shifokor tekshirgan (`approved`,
   `corrected`, `new`). Majburiy. Inglizcha atama va uning sinonimlari (`aliases`) uchrasa, o‘zbekcha atama ishlatiladi.
   Qavs ichidagi qism (masalan, “datchik (zond)” dagi “zond”) birinchi uchraganda yozilishi mumkin, keyin asosiy shakl.
   `general = 1` belgili 20 ta umumiy so‘z (liver, kidney …) oddiy so‘z sifatida tarjima qilinadi.
2. **Ko‘nikmadagi ⏳ atamalar** (`medical-uzbek-translator`): ilovadagi lug‘atda yo‘q bo‘lsa ishlatiladi, lekin
   hisobotning “Yangi terminlar” bo‘limiga yoziladi.
3. **Lug‘atda umuman yo‘q atama**: o‘zbek tibbiy adabiyotida qabul qilingan shakl tanlanadi va “Yangi terminlar”ga
   yoziladi. Shifokor tasdiqlagandan keyingina lug‘atga kiradi (`docs/glossary/UZI_atamalari_tekshirilgan.xlsx` →
   `tools/glossary/convert_uzi_glossary.py`).

Grammatik qo‘shimchalar gapga moslashtiriladi (“datchikni”, “o‘t pufagidagi”), tibbiy ma’no emas.

**Ma’lum ziddiyat (shifokor hal qiladi):** calcification — ilovada “kalsifikat”, ko‘nikmada “kalsinat /
kalsifikatsiya”. Hal bo‘lguncha ilovadagi shakl ishlatiladi. Lug‘atning 12 ta shubhali yozuvi
(`docs/glossary/review-2026-10-10.md`) ham hali ochiq.

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

## 9. Shifokor hal qiladigan ochiq qarorlar

| Savol | Vaqtincha qoida |
| --- | --- |
| calcification: “kalsifikat” yoki “kalsinat / kalsifikatsiya”? | “kalsifikat” |
| Santimetr belgisi: “cm” yoki “sm”? | “cm” (manbadagidek) |
| Ko‘nikmadagi ⏳ atamalar (24 ta) lug‘atga qo‘shilsinmi? | Ishlatiladi, “Yangi terminlar”ga yoziladi |
| Lug‘atning 12 ta shubhali yozuvi | Hozirgi shakl ishlatiladi |
