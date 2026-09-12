# Kayan X — Android Native

وكيل ذكاء اصطناعي محلي يعمل على Android بدون إنترنت.  
مبني على Kotlin + Jetpack Compose + llama.cpp عبر NDK/JNI + Storage Access Framework.

---

## جدول المحتويات

1. [المعمارية](#المعمارية)
2. [تفاصيل حجم التطبيق](#تفاصيل-حجم-التطبيق)
3. [Auto Backend Profiler](#auto-backend-profiler)
4. [متطلبات البناء](#متطلبات-البناء)
5. [إعداد NDK و llama.cpp](#إعداد-ndk-و-llamacpp)
6. [البناء](#البناء)
7. [وضع نموذج GGUF](#وضع-نموذج-gguf)
8. [مجلد Downloads](#مجلد-downloads)
9. [الصلاحيات](#الصلاحيات)
10. [تشغيل النموذج](#تشغيل-النموذج)
11. [Benchmark](#benchmark)
12. [الاختبارات](#الاختبارات)
13. [Debugging](#debugging)
14. [هيكل المشروع](#هيكل-المشروع)

---

## المعمارية

```
┌─────────────────────────────────────────────────────────┐
│                         UI Layer                        │
│  ChatScreen │ ModelScreen │ SettingsScreen (Compose)    │
│             MainViewModel (StateFlow)                   │
└─────────────────┬────────────────────────────┬──────────┘
                  │                            │
┌─────────────────▼──────────┐  ┌─────────────▼──────────┐
│       Agent Engine         │  │      Native Engine      │
│  AgentOrchestrator         │  │  LlamaEngine (Kotlin)   │
│  AgentState / AgentStep    │  │  ↕ JNI                  │
│  ToolRegistry              │  │  kayan_llama.so          │
│  FileTool × 9              │  │  llama.cpp C API         │
└────────┬───────────────────┘  │  DeviceProfiler          │
         │                      │  BenchmarkRunner         │
┌────────▼───────────────────┐  └──────────────────────────┘
│      Security Layer        │
│  PathGuard                 │
│  ConfirmationPolicy        │
└────────┬───────────────────┘
         │
┌────────▼───────────────────┐
│      File Bridge           │
│  SafFileManager (SAF)      │
│  PersistedUriStore         │
│  DocumentHandle            │
└────────────────────────────┘
```

### حلقة الوكيل (Agent Loop)

```
User → Planner (LLM) → Tool Selection → Policy/Permission
     → [Confirmation Dialog if HIGH/CRITICAL]
     → Execution → Observation → Verification → Re-plan → Final Answer
```

- كل خطوة تُقرر بناءً على نتيجة الخطوة السابقة (خطة ديناميكية، لا ثابتة).
- اللغوي يقترح أداة واحدة فقط في كل دورة.
- التحقق الحتمي يعمل بعد كل تنفيذ أداة.

---

## تفاصيل حجم التطبيق

> ⚠️ لا يوجد هدف وهمي لحجم الـ APK. فيما يلي الأرقام الحقيقية:

| المكون | الحجم التقريبي |
|--------|----------------|
| Kotlin + Compose + منطق التطبيق | ~5–8 MB |
| `kayan_llama.so` (arm64-v8a، بعد strip) | ~10–20 MB |
| **إجمالي APK للجهاز (ABI split)** | **~15–30 MB** |
| نموذج GGUF 1.5B (خارج APK) | ~900 MB – 1 GB |
| نموذج GGUF 3B (خارج APK) | ~1.8 – 2 GB |
| نموذج GGUF 7B (خارج APK) | ~3.8 – 4.5 GB |

### لماذا لا يُضمَّن النموذج في APK؟

1. نموذج 3B = ~2 GB → حد متجر Play هو 150 MB.
2. نموذج 7B = ~4 GB → أكبر من حد APK نفسه.
3. حتى نموذج 1.5B (~1 GB) يتجاوز المعقول بفارق كبير.

**الحل:** يختار المستخدم ملف GGUF من جهازه عبر SAF. يُحفظ الإذن ويظل ساريًا عبر الجلسات.

---

## Auto Backend Profiler

> تم استبدال `n_gpu_layers = 32` المُثبَّتة بمحلل تلقائي.

### كيف يعمل

يفحص `DeviceProfiler` عند التشغيل:

| المعلومة | المصدر |
|----------|--------|
| ذاكرة RAM الكلية والمتاحة | `ActivityManager.MemoryInfo` |
| عدد أنوية المعالج | `Runtime.availableProcessors()` |
| أقصى تردد للمعالج | `/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq` |
| **مزود GPU** | **EGL → `GL_RENDERER` string** |

### جدول n_gpu_layers الناتج

| RAM المتاحة | Adreno | Mali | Unknown |
|-------------|--------|------|---------|
| < 2 GB | 0 | 0 | 0 |
| 2–3 GB | 8 | 4 | 0 |
| 3–4 GB | 16 | 8 | 0 |
| 4–5 GB | 24 | 16 | 4 |
| 5–6 GB | 32 | 20 | 8 |
| > 6 GB | 35 | 28 | 12 |

يُضرب الناتج في معامل تعديل بحسب حجم النموذج:
- 1.5B → ×1.0
- 3B → ×0.85
- 7B → ×0.60

### التعديل اليدوي

يستطيع المستخدم تجاوز هذه القيم من شاشة الإعدادات. عند ذلك تظهر علامة "يدوي" في الواجهة وتُحفظ القيم. "إعادة ضبط تلقائي" تعيد الحساب من الجهاز.

---

## متطلبات البناء

| الأداة | الإصدار |
|--------|---------|
| Android Studio | Hedgehog 2023.1.1+ |
| Android SDK | API 35 |
| Android NDK | r26d+ |
| CMake | 3.22.1+ |
| JDK | 17 |
| Git | أي إصدار حديث |

---

## إعداد NDK و llama.cpp

### 1. تثبيت NDK

في Android Studio:
**SDK Manager → SDK Tools → NDK (Side by side) → تثبيت r26d+**

أو عبر command line:
```bash
sdkmanager "ndk;26.3.11579264" "cmake;3.22.1"
```

### 2. llama.cpp (يُجلب تلقائيًا)

CMakeLists يستخدم `FetchContent` لجلب llama.cpp:
```cmake
set(LLAMA_TAG "b3967" CACHE STRING "...")
FetchContent_Declare(llama_cpp GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git ...)
```

لتحديد إصدار مختلف:
```bash
./gradlew assembleDebug -PLLAMA_TAG=b4100
```

### 3. إعداد `local.properties`

```properties
sdk.dir=/home/user/Android/Sdk
ndk.dir=/home/user/Android/Sdk/ndk/26.3.11579264
```

---

## البناء

```bash
# Debug APK (كل ABIs في APK واحد للتسهيل)
./gradlew assembleDebug

# Release APK مقسم بـ ABI (arm64-v8a فقط للإنتاج)
./gradlew assembleRelease

# مشاهدة حجم كل مكون
./gradlew app:dependencies
./gradlew bundleRelease  # لحساب حجم AAB
```

الـ APK المُنتَج في:
```
app/build/outputs/apk/release/app-arm64-v8a-release.apk   ← للإنتاج
app/build/outputs/apk/debug/app-debug.apk                 ← للاختبار
```

---

## وضع نموذج GGUF

النموذج **لا يُضمَّن في APK**. اتبع هذه الخطوات:

1. حمّل نموذج GGUF على جهازك (مثلاً في مجلد `Downloads`):
   - [Qwen2.5-3B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF)
   - [Llama-3.2-3B-GGUF](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF)
   - [SmolLM2-1.7B-GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF)

2. افتح التطبيق → **النموذج** → **اختر ملف GGUF من الجهاز**.

3. يحفظ التطبيق إذن SAF؛ لن تحتاج لإعادة الاختيار عند إعادة التشغيل.

4. اضغط **تحميل النموذج**.

### ملاحظات الكميّة

| النوع | الحجم (3B) | السرعة | الجودة |
|-------|------------|--------|--------|
| Q8_0 | ~3.3 GB | بطيء | ممتاز |
| Q4_K_M | ~1.8 GB | متوسط | جيد جداً |
| Q4_0 | ~1.7 GB | أسرع | جيد |
| Q2_K | ~1.1 GB | أسرع | مقبول |

---

## مجلد Downloads

1. **النموذج ← شاشة النموذج**: يختار ملف GGUF واحد فقط.
2. **مجلد للوكيل ← شاشة النموذج**: يضغط "ربط مجلد Downloads" ويختار مجلداً.
   - يُحفظ كـ root باسم `downloads`.
   - الوكيل يصل إليه عبر `downloads:/filename.txt`.
3. يمكن ربط مجلدات إضافية مستقبلاً بنفس الآلية.

---

## الصلاحيات

| الصلاحية | الحالة | السبب |
|----------|--------|-------|
| `READ_EXTERNAL_STORAGE` | **غير مطلوبة** | SAF يكفي |
| `WRITE_EXTERNAL_STORAGE` | **غير مطلوبة** | SAF يكفي |
| `MANAGE_EXTERNAL_STORAGE` | **غير مطلوبة** | SAF يكفي |
| `INTERNET` | غير مستخدمة | يعمل offline |
| SAF (per-URI) | يُمنح عند الاختيار | نظام Android |

---

## تشغيل النموذج

1. اختر نموذج GGUF (شاشة النموذج).
2. اختر Preset (1.5B / 3B / 7B) من الإعدادات.
3. راجع الإعدادات التلقائية المقترحة من المحلل.
4. اضغط "تحميل النموذج".
5. انتقل لشاشة المحادثة وأرسل مهمة.

---

## Benchmark

من شاشة **الإعدادات**:
1. حمّل النموذج أولاً.
2. اضغط **تشغيل الاختبار**.

المقاييس المُبلَّغ عنها:

| المقياس | الوصف |
|---------|-------|
| وقت التحميل | الوقت منذ `loadModel()` حتى جهوزية الاستدلال |
| أول رمز (latency) | الوقت من إرسال الطلب حتى أول رمز |
| رموز/ثانية | معدل التوليد خلال 64 رمزاً |
| استهلاك RAM | الفرق في استخدام RAM قبل وبعد الاستدلال |

---

## الاختبارات

### اختبارات Unit (JVM)

```bash
./gradlew test
```

تشمل:
- `DeviceProfilerTest` — اختبار جدول n_gpu_layers بشكل كامل.
- `InferenceConfigTest` — التحقق من قيم الإعداد وValidation.
- `PathGuardTest` — path traversal وامتدادات المحظورة.

### اختبارات Instrumentation (Android)

```bash
./gradlew connectedAndroidTest
```

تشمل:
- `AgentOrchestratorTest` — التحقق من سلسلة Tool → PathGuard → SAF.
- `SafFileManagerTest` — عمليات الملفات على التخزين الداخلي.

---

## Debugging

### NDK Crash

```bash
# اجلب tombstone من الجهاز
adb pull /data/tombstones/tombstone_XX /tmp/
# ثم فك رموز الـ stack trace
ndk-stack -sym app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a \
          -dump /tmp/tombstone_XX
```

### Logcat للـ LlamaEngine

```bash
adb logcat -s KayanLlama:D
```

### اختبار الإعدادات بدون نموذج

شاشة الإعدادات تعمل كاملاً بدون نموذج محمّل — يمكن فحص DeviceProfile ومقارنة الإعدادات التلقائية.

### مشكلة: Model load failed

1. تحقق من صيغة الملف (يجب أن يكون GGUF، وليس GGML القديم).
2. تحقق من أن الملف غير تالف (`sha256sum`).
3. جرب تقليل `nCtx` إلى 2048 إذا كانت الذاكرة مشكلة.
4. تحقق من Logcat: `adb logcat -s KayanLlama`.

### مشكلة: SAF لا يجد الملف

1. تأكد من الضغط على "ربط مجلد Downloads" أولاً.
2. تأكد أن المسار يبدأ بـ `downloads:/` أو `workspace:/`.
3. تأكد من أن إذن SAF لا يزال ساريًا (الأذونات المُثبَّتة).

---

## هيكل المشروع

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt          # NDK build — يجلب llama.cpp تلقائياً
│   └── llama_jni.cpp           # JNI bridge كامل
├── kotlin/com/kayan/x/
│   ├── engine/
│   │   ├── LlamaEngine.kt      # Kotlin wrapper — JNI + lifecycle
│   │   ├── InferenceConfig.kt  # إعدادات الاستدلال + ModelPreset
│   │   └── profiler/
│   │       ├── DeviceProfiler.kt   # Auto n_gpu_layers (يستبدل =32)
│   │       └── BenchmarkRunner.kt  # قياسات الأداء
│   ├── agent/
│   │   ├── AgentOrchestrator.kt  # حلقة الوكيل الكاملة
│   │   ├── AgentState.kt
│   │   └── AgentStep.kt + VerificationResult
│   ├── tools/
│   │   ├── FileTool.kt         # 9 أدوات × PathGuard × SAF
│   │   └── ToolRegistry.kt
│   ├── files/
│   │   ├── SafFileManager.kt   # SAF — لا sdcard ثابت
│   │   ├── DocumentHandle.kt   # معرّف الملف الآمن
│   │   └── PersistedUriStore.kt
│   ├── safety/
│   │   ├── PathGuard.kt        # حماية path traversal
│   │   └── ConfirmationPolicy.kt
│   ├── model/
│   │   ├── ModelManager.kt     # سجل GGUF — لا model في APK
│   │   └── ModelInfo.kt
│   └── ui/
│       ├── MainViewModel.kt
│       └── screens/
│           ├── ChatScreen.kt
│           ├── ModelScreen.kt   # عرض حقيقي لحجم APK vs model
│           └── SettingsScreen.kt # DeviceProfile + Manual override
```
