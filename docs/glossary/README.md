# UZI lug‘ati

| Fayl | Vazifasi |
|---|---|
| `UZI_atamalari_tekshirilgan.xlsx` | Manba: shifokor tekshirgan Excel fayli. Tahrir faqat shu faylda qilinadi |
| `../../tools/glossary/convert_uzi_glossary.py` | Excel faylini ilova asset'iga o‘giradi va natijani tekshiradi |
| `../../app/src/main/assets/uzi_glossary.tsv` | Ilova o‘qiydigan fayl (avtomatik yaratiladi, qo‘lda tahrirlanmaydi) |
| `review-2026-10-10.md` | Tibbiy jihatdan shubhali deb topilgan yozuvlar ro‘yxati |

## Lug‘atni yangilash
```bash
pip install openpyxl
python3 tools/glossary/convert_uzi_glossary.py docs/glossary/UZI_atamalari_tekshirilgan.xlsx app/src/main/assets/uzi_glossary.tsv
python3 -m unittest tools/tests/test_uzi_glossary.py -v
```
Skript har bir holat bo‘yicha sonlarni chiqaradi. Bir inglizcha shakl ikki xil atamaga tegishli bo‘lsa, ogohlantiradi.
Yozuvlar soni o‘zgarsa, quyidagi ikki testdagi kutilgan sonlarni ham yangilang (hozir 228 jami / 171 To‘g‘ri / 56 Tuzatildi / 1 Yangi / 20 umumiy):
- `tools/tests/test_uzi_glossary.py`
- `app/src/test/.../UziGlossaryTest.kt`

## Holat qoidalari (G ustuni)
| G | Lug‘atga nima tushadi |
|---|---|
| To‘g‘ri | E va F |
| Tuzatildi | H va I. Ulardan biri bo‘sh bo‘lsa, E yoki F saqlanadi |
| O‘chirish | Kiritilmaydi |
| Yangi | Kiritiladi (H/I to‘ldirilgan bo‘lsa, ular ishlatiladi) |
| Tekshirilmagan va boshqalar | Kiritilmaydi, skript ro‘yxatini chiqaradi |

J = "Ha" bo‘lgan umumiy so‘zlar (jigar, gain va hokazo) subtitr ostida chip sifatida chiqmaydi. Ularning izohi faqat so‘zning o‘zi bosilganda ko‘rinadi.
