# Poliglot: kunlik karta (SHOKALON)

Owner TZ, 2026-11-17. Kontent: `app/src/main/assets/poliglot_content.json` (1–7-kunlar; 8–30 keyin qo'shiladi).

## Acceptance criteria
- Bosh menyudagi Poliglot kartasi `PoliglotActivity` ni ochadi; sahifa `https://appassets.androidplatform.net/assets/poliglot/index.html`
  (WebViewAssetLoader "/assets/" prefiksini olib tashlaydi — build 242 dagi "/poliglot/" manzili bo'sh sahifa berardi).
- Bir kun = bitta karta: hikoya (yangi so'zlar to'q sariq, takrorlash ko'k, bosilsa tarjima), hadis va manbasi,
  3 savol (yozish yoki 🎤), qayta aytish qolipi, 3 gap yozish, "Let's talk" (Claude ilovasi/claude.ai, matn nusxalanadi),
  arab harflari va 3 so'z. Har vazifada checkbox.
- Takrorlash: `learned_day + [1, 3, 6, 13, 29]`, JSON `review_from_days`, kechagi "unutdim" so'zlari.
- Kun bajarildi: vazifalarning yarmidan ko'pi. Seriya va "x/30" sarlavhada.
- Kechki hisobot TZ formatida, "Nusxalash" va "Yuborish".
- Ertalab (vazifa) va kechqurun (hisobot) eslatmalari, vaqti sozlanadi.
- Progress `files/poliglot-v2.json` da (localStorage — zaxira). Eski dastur `classic.html` da saqlanib qoldi.

## Validation
- `PoliglotFilesTest` (kontent tuzilmasi, 28 harf, xavfsiz fayl nomlari, Claude havolasi, ikki eslatma).
- Sahifa Playwright'da (Pixel 7) ochildi: belgilash, hisobot, 3- va 7-kunlar, JS xatolarsiz.
- Telefonda: ovoz, mikrofon, eslatma va Claude ochilishi — tekshirilmagan.

## Deviation
- Poliglot ekranida ikkinchi WebView bor (alohida Activity, faqat o'z assetlari, tarmoqqa chiqmaydi); YouTube sahifasining
  yagona WebView qoidasi o'zgarmagan.
