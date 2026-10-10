# UZI ovoz sinovi (shifokor quloq bilan tekshiradi)

Ilova: Sozlamalar → "O‘zbekcha ovoz" → "Ovoz sinovi" → **Sardor** / **Madina** tugmasi.
Ilovada faqat shu ikki ovoz bor (Microsoft Edge `uz-UZ-SardorNeural`, `uz-UZ-MadinaNeural`). **Neurolink ilovaga ulanmagan**, shuning uchun uni sinab bo‘lmaydi.

Sinov matni:
> O‘zbekistonlik UZI shifokori jigar, portal vena va umumiy o‘t yo‘lini tekshiradi. Gipoexogen o‘choq, echogenlik va siljish to‘lqini elastografiyasi natijalarini baholaydi. O‘ng bo‘lakdagi to‘qimaning qattiqligi kilopaskalda ifodalanadi.

Har bir ovoz uchun belgilang: ✅ to‘g‘ri, ❌ xato, ❓ aniq emas.

| Tekshiriladigan narsa | Sardor | Madina | Izoh |
|---|---|---|---|
| **o‘**: O‘zbekiston, o‘t, o‘choq, O‘ng, bo‘lak, to‘qima, to‘lqini | | | |
| **g‘**: (sinov matnida yo‘q; quyidagi qo‘shimcha jumlada bor) | | | |
| jigar, portal vena: **g** va **o** oddiy o‘qiladi | | | |
| UZI (u-ze-i yoki "uzi") | | | |
| Gipoexogen, echogenlik | | | |
| elastografiyasi, kilopaskalda | | | |
| Jumlalar orasidagi pauza | | | |
| Tezlik va tabiiylik | | | |

Qo‘shimcha jumlalar. Ularni videoda yoki "Ovoz sinovi"da sinab ko‘ring. Birliklar va qisqartmalar uchun "sinov" kalitlarini yoqib va o‘chirib solishtiring:
1. "Bog‘langan g‘ovak tuzilma, so‘nggi o‘lchov 12 kPa." (g‘, kPa)
2. "Rezistivlik indeksi (RI) 0,7; TGC ni sozlang." (RI, TGC, o‘nli kasr)
3. "Jigar venasining diametri 8 mm, portal venaning oqimi gepatopetal." (g/o o‘zgarmasligi, mm)

Natija: xato topilgan so‘z va naqshlarni yozing. Faqat shu yerda tasdiqlangan xatolar uchun yangi talaffuz qoidasi qo‘shiladi (`prepareUzbekSpeech`).
Kalitlar yoqilganda qaysi qoidalar ishlaganini `adb logcat -s EdgeSpeech` orqali ko‘rish mumkin.
