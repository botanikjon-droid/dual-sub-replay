# ML Kit discovers these manifest-named registrars through reflection. R8 full
# mode otherwise removes their no-argument constructors (verified in release QA).
-keep,allowoptimization class com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar { public <init>(); }
-keep,allowoptimization class com.google.mlkit.common.internal.CommonComponentRegistrar { public <init>(); }

# Kuromoji loads its dictionary with Class.getResourceAsStream relative to its own package, so
# its classes must keep their names and packages or Japanese word grouping falls back to heuristics.
-keep class com.atilika.kuromoji.** { *; }
