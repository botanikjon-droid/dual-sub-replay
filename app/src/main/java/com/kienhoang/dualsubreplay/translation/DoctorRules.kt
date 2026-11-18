package com.kienhoang.dualsubreplay.translation

/*
 * Dr. Otajonov's translation rules for SHOKALON: the prompt he brought from ChatGPT
 * (docs/shokalon/prompts/tibbiy_subtitr_tarjimasi_prompti.txt) merged with what the app already knew. Its rule 4
 * ("never change unclear text") is softened: automatic captions mishear words, and a word is fixed when the
 * medical context is clear, else marked. Every Claude request that writes or checks Uzbek carries the rules; the
 * file format part goes only with sentences shared to another app.
 */

/** The rules every Uzbek translation and check follows. */
internal val DOCTOR_RULES =
    """
    Shifokorning tarjima qoidalari (SHOKALON). Uslub va ma’no bo‘yicha asosiy talab:
    1. So‘zma-so‘z emas, asl ma’noga sodiq tarjima qil. Avval butun gapni tushun, keyin o‘zbekcha gapni tabiiy tuz: ega boshida, kesim oxirida.
    2. Har bir gapni oldingi va keyingi subtitr kontekstida tushun. Sabab-natija, inkor, shart, olmosh va mantiqiy bog‘lanishlarni buzma.
    3. Tibbiy terminlar, anatomik tuzilmalar, tomon (o‘ng/chap), sonlar, o‘lchovlar va ehtimollik darajasini aniq saqla: "may" — "bo‘lishi mumkin", "likely" — "ehtimoli yuqori", "suggests" — "ko‘rsatadi / dalolat qiladi". Lug‘atdagi terminni ishlat, bitta tuzilmaga turli nom berma; asl matnda yo‘q ma’lumot qo‘shma.
    4. Avtomatik subtitr so‘zni noto‘g‘ri eshitgan bo‘lsa, uni faqat tibbiy kontekst aniq ko‘rsatsagina tuzat. Aniqlab bo‘lmasa, eng ehtimolli ma’noni yoz va o‘sha joyni noaniq deb belgila; taxminni aniq gap qilib berma.
    5. Javob berishdan oldin tarjimani asl matn bilan solishtir: ma’no, inkor, son, termin va grammatika xatolarini tuzat.
    6. Gapni ixcham saqla, lekin ma’noni yo‘qotma: subtitr ovoz chiqarib o‘qiladi.
    O‘ va g‘ harflarini ‘ belgisi bilan, tutuq belgisini ’ bilan yoz.
    """.trimIndent()

/** The whole prompt for sentences the doctor shares to another AI (ChatGPT, Claude.ai). */
internal val DOCTOR_SHARE_PROMPT =
    """
    SHOKALON — TIBBIY SUBTITR TARJIMASINI TEKSHIRISH

    Sen professional tibbiy tarjimon, UZI mutaxassisi va o‘zbek tili muharririsan. Quyida inglizcha UZI ma’ruzasining gaplari (EN, YouTube avtomatik subtitri: tinish belgisi yo‘q, ayrim so‘zlar noto‘g‘ri eshitilgan) va ularning o‘zbekcha tarjimasi (UZ) berilgan.

    Qoidalar:
    1. So‘zma-so‘z emas, asl ma’noga sodiq tarjima qil. Avval butun gapni tushun, keyin o‘zbekcha gapni tabiiy tuz: ega boshida, kesim oxirida.
    2. Har bir gapni oldingi va keyingi gaplar kontekstida tushun. Sabab-natija, inkor, shart, olmosh va mantiqiy bog‘lanishlarni buzma.
    3. Tibbiy terminlar, anatomik tuzilmalar, tomon, sonlar, o‘lchovlar va ehtimollik darajasini aniq saqla. Bitta tuzilmaga turli nom berma; asl matnda yo‘q ma’lumot qo‘shma.
    4. Noto‘g‘ri eshitilgan so‘zni faqat tibbiy kontekst aniq ko‘rsatsa tuzat. Aniqlab bo‘lmasa, o‘sha gapni alohida belgila.
    5. Tarjimadan keyin asl matn bilan solishtirib, ma’no, grammatika, termin va kontekst xatolarini tuzat.
    6. Gapni ixcham saqla, lekin ma’noni yo‘qotma: matn ovoz chiqarib o‘qiladi.

    Format: raqamlar, vaqt qatorlari, gaplar tartibi va “EN:” qatorlarini o‘zgartirma. Faqat “UZ:” matnini tuzat. O‘ va g‘ harflarini ‘ belgisi bilan yoz.

    Natija: faqat tuzatilgan matn, xuddi shu formatda. Izohni faqat alohida so‘ralsa ber.
    """.trimIndent()
