/*
 * Poliglot kunlik dastur mantig'i: intervalli takrorlash, hikoyadagi so'zlarni topish, kun hisobi va
 * kechki hisobot. Interfeysdan alohida, shuning uchun Node'da test qilinadi (tools/poliglot_course_test.js).
 */
(function (root) {
  "use strict";

  var DAY_MS = 86400000;
  var TOTAL_DAYS = 30;
  var ARABIC_TASK = "arabic_letters";

  /** "2026-11-16" -> mahalliy yarim tun (soat mintaqasi siljishisiz). */
  function parseDate(iso) {
    var p = String(iso || "").split("-").map(Number);
    if (p.length !== 3 || p.some(isNaN)) return null;
    return new Date(p[0], p[1] - 1, p[2]);
  }

  function isoDate(date) {
    function two(n) { return (n < 10 ? "0" : "") + n; }
    return date.getFullYear() + "-" + two(date.getMonth() + 1) + "-" + two(date.getDate());
  }

  function uzDate(date) {
    function two(n) { return (n < 10 ? "0" : "") + n; }
    return two(date.getDate()) + "." + two(date.getMonth() + 1) + "." + date.getFullYear();
  }

  /** Bugun dasturning nechanchi kuni: boshlanish kuni 1-kun; 1..30 oralig'ida. */
  function dayNumber(startIso, today) {
    var start = parseDate(startIso);
    if (!start) return 1;
    var now = new Date(today.getFullYear(), today.getMonth(), today.getDate());
    var n = Math.round((now - start) / DAY_MS) + 1;
    return Math.max(1, Math.min(TOTAL_DAYS, n));
  }

  /** N-kunning sanasi. */
  function dateOfDay(startIso, day) {
    var start = parseDate(startIso) || new Date();
    return new Date(start.getFullYear(), start.getMonth(), start.getDate() + day - 1);
  }

  function dayOf(content, n) {
    var days = (content && content.days) || [];
    for (var i = 0; i < days.length; i++) if (days[i].day === n) return days[i];
    return null;
  }

  /**
   * N-kunda takrorlanadigan so'zlar: learned_day + [1, 3, 6, 13, 29] kunlari o'rganilgan so'zlar,
   * JSON'dagi review_from_days, va kechagi hisobotda "unutdim" deb belgilangan so'zlar.
   * Natija: [{en, uz, from}] — har so'z bir marta, bugungi yangi so'zlarsiz.
   */
  function reviewWords(content, n, forgottenYesterday) {
    var offsets = (content.spaced_repetition && content.spaced_repetition.review_offsets_days) || [1, 3, 6, 13, 29];
    var fromDays = offsets.map(function (o) { return n - o; }).filter(function (d) { return d >= 1; });
    var today = dayOf(content, n);
    ((today && today.review_from_days) || []).forEach(function (d) { if (fromDays.indexOf(d) < 0) fromDays.push(d); });
    var todayNew = {};
    ((today && today.new_words) || []).forEach(function (w) { todayNew[w.en.toLowerCase()] = true; });
    var seen = {};
    var out = [];
    function add(word, from) {
      var key = word.en.toLowerCase();
      if (seen[key] || todayNew[key]) return;
      seen[key] = true;
      out.push({ en: word.en, uz: word.uz, from: from });
    }
    fromDays.sort(function (a, b) { return b - a; }).forEach(function (d) {
      var day = dayOf(content, d);
      ((day && day.new_words) || []).forEach(function (w) { add(w, d); });
    });
    (forgottenYesterday || []).forEach(function (en) {
      for (var d = 1; d < n; d++) {
        var day = dayOf(content, d);
        var hit = ((day && day.new_words) || []).filter(function (w) { return w.en === en; })[0];
        if (hit) { add(hit, "unutilgan"); return; }
      }
    });
    return out;
  }

  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

  /**
   * So'z yoki iboraning hikoyadagi qolipi: qavsdagi izoh tashlanadi, "..." o'rnida 1–2 so'z bo'lishi mumkin,
   * oxirgi so'z -s/-es/-ed/-d/-ing qo'shimchasi bilan ham topiladi ("smile" -> "smiles").
   */
  function termPattern(en) {
    var clean = String(en).replace(/\([^)]*\)/g, " ").trim();
    var parts = clean.split(/\s*(?:\.\.\.|…)\s*/).filter(Boolean);
    var body = parts.map(function (part) {
      return part.split(/\s+/).map(escapeRe).join("\\s+");
    }).join("\\s+[\\w'’-]+(?:\\s+[\\w'’-]+)?\\s+");
    if (/\w$/.test(clean)) body += "(?:s|es|ed|d|ing)?";
    return new RegExp("\\b" + body + "\\b", "gi");
  }

  /**
   * Hikoyani bo'laklarga ajratadi: [{text}] yoki [{text, kind: "new"|"review", uz, en}].
   * Uzun ibora birinchi; bir so'z ikki ma'noda bo'lsa (patient = bemor / sabrli) ikkalasi ko'rsatiladi.
   */
  function storySegments(story, newWords, review) {
    var terms = [];
    (newWords || []).forEach(function (w) { terms.push({ en: w.en, uz: w.uz, kind: "new" }); });
    (review || []).forEach(function (w) { terms.push({ en: w.en, uz: w.uz, kind: "review" }); });
    var byKey = {};
    terms.forEach(function (t) {
      var key = String(t.en).replace(/\([^)]*\)/g, "").trim().toLowerCase();
      if (byKey[key]) {
        byKey[key].uz += " / " + t.uz;
        byKey[key].en += " / " + t.en;
      } else {
        byKey[key] = { en: t.en, uz: t.uz, kind: t.kind, key: key };
      }
    });
    var merged = Object.keys(byKey).map(function (k) { return byKey[k]; });
    merged.sort(function (a, b) { return b.key.length - a.key.length; });
    var taken = [];
    merged.forEach(function (t) {
      var re = termPattern(t.key);
      var m;
      while ((m = re.exec(story)) !== null) {
        var s = m.index, e = s + m[0].length;
        var clash = taken.some(function (x) { return s < x.e && e > x.s; });
        if (!clash) taken.push({ s: s, e: e, term: t });
        if (m[0].length === 0) re.lastIndex++;
      }
    });
    taken.sort(function (a, b) { return a.s - b.s; });
    var out = [];
    var at = 0;
    taken.forEach(function (x) {
      if (x.s > at) out.push({ text: story.slice(at, x.s) });
      out.push({ text: story.slice(x.s, x.e), kind: x.term.kind, uz: x.term.uz, en: x.term.en });
      at = x.e;
    });
    if (at < story.length) out.push({ text: story.slice(at) });
    return out;
  }

  function taskIds(content) {
    return ((content && content.daily_tasks_template) || []).map(function (t) { return t.id; });
  }

  /** Kun "bajarildi": vazifalarning yarmidan ko'pi belgilangan. */
  function isDayDone(content, record) {
    var ids = taskIds(content);
    if (!record || !record.tasks || ids.length === 0) return false;
    var done = ids.filter(function (id) { return record.tasks[id]; }).length;
    return done * 2 > ids.length;
  }

  function completedDays(content, state) {
    var n = 0;
    for (var d = 1; d <= TOTAL_DAYS; d++) if (isDayDone(content, state.days[d])) n++;
    return n;
  }

  /** Seriya: [day] dan (u hali bajarilmagan bo'lsa kechadan) orqaga ketma-ket bajarilgan kunlar. */
  function streak(content, state, day) {
    var d = isDayDone(content, state.days[day]) ? day : day - 1;
    var n = 0;
    while (d >= 1 && isDayDone(content, state.days[d])) { n++; d--; }
    return n;
  }

  /** Kechki hisobot matni (TZ 4-band). */
  function eveningReport(content, state, n) {
    var day = dayOf(content, n) || { topic_en: "", arabic: { letters: [] } };
    var rec = state.days[n] || {};
    var tasks = rec.tasks || {};
    var ids = taskIds(content);
    var en = ids.filter(function (id) { return id !== ARABIC_TASK; });
    var ar = ids.filter(function (id) { return id === ARABIC_TASK; });
    function count(list) { return list.filter(function (id) { return tasks[id]; }).length + "/" + list.length; }
    var letters = ((day.arabic && day.arabic.letters) || []).join(" ");
    var forgotten = (rec.forgotten || []).join(", ") || "yo'q";
    return [
      "Poliglot hisobot — " + n + "-kun (" + uzDate(dateOfDay(state.start, n)) + ")",
      "Ingliz (" + day.topic_en + "): " + count(en) + " vazifa",
      "Arab (" + (letters || "harflar") + "): " + count(ar) + " vazifa",
      "Vaqt: " + (rec.minutes ? rec.minutes : "__") + " daqiqa · Ravonlik: " + (rec.fluency ? rec.fluency : "_") + "/5",
      "Unutilgan so'zlar: " + forgotten,
      "Seriya: " + streak(content, state, n) + " kun · Bajarilgan kunlar: " + completedDays(content, state) + "/" + TOTAL_DAYS,
      "Izoh: " + (rec.note ? rec.note : "..."),
    ].join("\n");
  }

  /** "Let's talk" uchun Claude'ga boradigan matn. */
  function agentPrompt(content, n, mode, review) {
    var day = dayOf(content, n) || { topic_en: "", new_words: [] };
    var words = day.new_words.map(function (w) { return w.en; }).join(", ");
    var old = (review || []).map(function (w) { return w.en; }).join(", ");
    var modes = { suhbat: "conversation", hikoya: "story", rol: "role-play" };
    return "Use my english-speaking-partner skill. Mode: " + (modes[mode] || "conversation") +
      ". Poliglot day " + n + ", topic: " + day.topic_en + ". Today's new words: " + words +
      (old ? ". Review words: " + old : "") + ". Let's talk!";
  }

  var api = {
    TOTAL_DAYS: TOTAL_DAYS,
    parseDate: parseDate,
    isoDate: isoDate,
    uzDate: uzDate,
    dayNumber: dayNumber,
    dateOfDay: dateOfDay,
    dayOf: dayOf,
    reviewWords: reviewWords,
    termPattern: termPattern,
    storySegments: storySegments,
    taskIds: taskIds,
    isDayDone: isDayDone,
    completedDays: completedDays,
    streak: streak,
    eveningReport: eveningReport,
    agentPrompt: agentPrompt,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.PGCore = api;
})(this);
