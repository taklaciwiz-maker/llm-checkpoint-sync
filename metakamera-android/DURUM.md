# MetaKamera: Proje Durumu

**Tarih:** 1 Kasım 2026
**Genel ilerleme:** yaklaşık **%80**

MetaKamera, fotoğraf ve videoların metadata'sındaki cihaz kimliğini değiştiren bir Android uygulamasıdır. Bunu Make/Model/Lens/Software alanlarında, ICC profilinde, XMP'de ve video anahtarlarında yapar. Örnek: Huawei Pura 90s Pro Max ile çekilmiş bir fotoğraf, iPhone 18 Pro Max ile çekilmiş gibi görünür. Değiştirilmiş dosya tek dokunuşla galeriye kopyalanabilir.

---

## Bu zip'in içinde neler var

| Klasör / dosya | Açıklama |
|---|---|
| `kaynak/` | Projenin tamamı. `metakamera-android/` klasörü ve GitHub Actions dosyası. Son entegre commit `13382b4`. |
| `apk/MetaKamera-1.0.0.apk` | Şu anki çalışan sürüm. Kamera/çözünürlük seçimi ve ana ekrandaki hızlı arama bu APK'da **henüz yok**, aşağıya bakın. |
| `devam-eden-isler/*.patch` | Yarıda kalan iki işin kodu. Test edilmedi. |
| `arastirma/` | Telefon kimlik araştırması, kamera modülü araştırması, format/marka kuralları (JSON) ve veritabanı üretici betik. |
| `metakamera-git.bundle` | Tüm git geçmişi (`mk-main` dalı). `git clone metakamera-git.bundle MetaKamera` ile açılır. |
| `DURUM.md` | Bu dosya. |

Aynı kod GitHub'da da duruyor:
- Repo: `taklaciwiz-maker/llm-checkpoint-sync`
- Dal: `ccr-d99ebc4f-795ier`
- Klasör: `metakamera-android/`

---

## ✅ Yapılanlar

### 1. Araştırma (%100)
- **357 amiral gemisi telefon (2021 – Kasım 2026).**
  - Markalar: Apple, Samsung, Google, Huawei, Honor, Xiaomi, OnePlus, OPPO, realme, vivo, Sony, Nothing, Motorola, ASUS.
  - Her telefon için EXIF'e yazdığı **tam** Make/Model değeri, bölge model kodları, Software, LensMake/LensModel ve HostComputer araştırıldı.
  - Model kodları KHwang9883/MobileModels listesi ve Google Play `supported_devices.csv` ile karşılaştırıldı.
- **Gerçek dosyalarla doğrulanan kurallar:**
  - Samsung 2024 ve sonrası Model alanına pazarlama adını yazıyor ("Galaxy S24 Ultra"). Eskiler `SM-…` kodunu yazıyor.
  - Huawei HarmonyOS 4 ve sonrası "HUAWEI Pura 80" biçimini kullanıyor.
  - Apple "iPhone 17 Pro Max" yazıyor. LensModel "… back triple camera 6.765mm f/1.78" biçiminde.
- **Sizin telefonunuz:** Huawei **Pura 90s Pro Max**, global kod `SCA-LX9` (Çin sürümü `SCA-AL00`).
- **Kamera modülleri (356 telefon):** Ana, geniş açı ve telefoto için ayrı ayrı (Ultra modellerde 3x+5x gibi birden fazla telefoto) şu bilgiler var:
  - sensör MP,
  - **varsayılan çıktı çözünürlüğü** ve yüksek çözünürlük modları (örn. S25 Ultra: 4000×3000 / 8160×6120 / 16320×12240),
  - gerçek odak uzaklığı (mm), 35 mm eşdeğeri, diyafram (f/),
  - Apple/Pixel için kameraya özel LensModel metni.
- **Marka ICC kuralları:**

  | Marka | ICC kuralı |
  |---|---|
  | Apple | Başlık alanları `APPL`/`appl`, açıklama "Display P3" |
  | Samsung | Başlık alanları `SEC ` |
  | Google | Başlık alanları `GOOG` |
  | Xiaomi | Üretici `XM  ` |
  | Huawei | Başlık alanları **boş**; marka bilgisi telif (cprt) metninde: "Huawei Device Co., Ltd. 2020" |

### 2. Metadata motoru (saf Java, `src/com/metakamera/core`): temel kısım %100, 3. tur ~%60
- **EXIF (TIFF):** Mevcut veriyi yerinden oynatmadan ("append-only") düzenler, böylece üreticinin MakerNote ofsetleri bozulmaz.
  - Make, Model, Software, HostComputer, LensMake ve LensModel değiştirilir.
  - MakerNote, seri numaraları, ImageUniqueID, Xiaomi'ye özel etiketler ve eski markayı içeren açıklama/yorumlar silinir; bu alanların baytları da sıfırlanır.
- **ICC profili:**
  - Başlıktaki cihaz üreticisi, model, CMM, platform ve oluşturan alanları değişir.
  - dmnd/dmdd metinleri değişir.
  - **Telif (cprt) metni ve P3 profil açıklaması** hedef markaya göre değişir.
  - MD5 profil kimliği yeniden hesaplanır.
- **XMP:** tiff:Make/Model, lens ve CreatorTool alanları güncellenir; paket boyutu korunur.
- **Format desteği:**

  | Format | Ne yapılıyor |
  |---|---|
  | JPEG | EXIF, parçalı ICC ve XMP düzenlenir; MPF (çoklu görüntü) ofsetleri düzeltilir; EOI sonrası veriler (hareketli fotoğraf videosu, gain map) bayt bayt korunur. |
  | HEIC/HEIF/AVIF | meta/iloc yeniden yazılır; Exif/XMP öğeleri ve colr ICC düzenlenir; eski veri sıfırlanır; birden fazla Exif öğesi (örn. spatial photo) desteklenir. |
  | PNG | eXIf, iCCP, iTXt XMP |
  | WebP | EXIF, ICCP, XMP, VP8X |
  | MP4/MOV/3GP | `com.apple.quicktime.make/model/software`, `com.android.*`, üretici anahtarları (oplus/honor/xiaomi…), iz düzeyindeki `lens_model` ve `focal_length.35mm_equivalent`, Samsung smta/mdln, udta ©mak/©mod. stco/co64/tfhd/tfra ofsetleri kaydırılır. Dosya parça parça okunur, GB'lık videolar sorun olmaz. |

- **Testler:** 182 JVM testi (`tools/test-core.sh`) var; 173'ü birleştirilmiş hâlde, kalan 9'u (ICC/video) kendi çalışma ağacında ayrıca geçti. Ayrıca gerçek dosyalarla denendi: iPhone 13 Pro, Galaxy S24/S25 Ultra, Xiaomi, Nokia HEIC'leri ve Canon/Sony/Olympus JPEG'leri.
  - **Pikseller birebir aynı kalıyor.**
  - exiftool doğrulama uyarıları artmıyor.

### 3. Android uygulaması (`src/com/metakamera/app`): temel kısım %100
- **Seçim:** "Galeriden Seç" (fotoğraf seçici) ve "Dosyalardan Seç" (konum bilgisini korur). Birden çok dosya seçilebilir.
- **Telefon seçici:** Aranabilir; marka, model, model kodu ve yıla göre arar; Türkçe İ/ı uyumlu. Bölge model kodu seçilebilir.
- **Seçenekler:** ICC, lens, yazılım, marka izleri ve XMP. "Gelişmiş" bölümünde elle düzenleme yapılabilir.
- **İşleme:** Arka planda yapılır; ilerleme çubuğu ve dosya başına "önce → sonra" raporu gösterilir.
- **Galeriye Kopyala / Tümünü Galeriye Kopyala:** DCIM/Camera veya MetaKamera albümüne kaydeder. Çekim tarihi korunur.
- **Paylaş.**
- Açık/koyu tema, Android 15/16 edge-to-edge desteği. Bağımsız kod incelemesi yapıldı, bulunan hatalar düzeltildi.

### 4. Derleme ve CI (%100)
- `build.sh` Gradle olmadan derler: aapt → javac → dx → zipalign → apksigner. Çıktı imzalı APK'dır (minSdk 26, targetSdk 36).
- GitHub Actions (`.github/workflows/metakamera-apk.yml`):
  - Her push'ta testleri çalıştırır ve APK üretir.
  - Commit mesajında `[release]` varsa veya iş akışı elle başlatılırsa GitHub Release yayınlar.

---

## 🟡 Yarıda kalan işler (`devam-eden-isler/`)

İki yama var. İkisi de **bitmemiş ve test edilmemiş** kod içeriyor. Repo kök klasöründe şöyle uygulanır:

```bash
git apply --3way devam-eden-isler/01-motor-kamera-optigi-ve-gomulu-exif-temizligi.patch
git apply --3way devam-eden-isler/02-arayuz-kamera-cozunurluk-secimi-ve-hizli-arama.patch
```

1. **01 – Motor: kamera optiği ve gömülü EXIF temizliği.** Taban commit `0e082d4`.
   - `ExifTiff` şu alanları yazacak: FocalLength, FNumber + ApertureValue, FocalLengthIn35mmFormat, PixelX/YDimension, LensSpecification (`tiff/Optics.java`).
   - `ExifTiff.scrubInPlace(...)`: boyutu değiştirmeden ikincil EXIF bloklarını temizler (`tiff/TiffScrubber.java`).
   - "OLYMPUS DIGITAL CAMERA" gibi marka kelimesi içeren açıklamalar silinecek.
   - XMP'de exif:FocalLength/FNumber/PixelX… güncellenecek.
2. **02 – Arayüz: kamera ve çözünürlük seçimi, hızlı arama.** Taban commit `454e6ff`.
   - Telefon seçildikten sonra "Kamera" seçimi: Ana / Geniş açı / Telefoto (MP, f/, mm ile).
   - "Çözünürlük" seçimi: örn. "24 MP · 5712 × 4284 (varsayılan)".
   - Telefon listesinde her modelin kamera özeti görünecek.
   - Ana ekranda **hızlı arama kutusu**: "s25ultra", "scalx9", "200 mp" gibi aramalar.
   - `SpecBuilder` seçilen kameranın odak, diyafram, lens ve EXIF çözünürlüğünü dosya bazında yazacak.
   - Not: `IccIdentity.copyrightText` / `p3DescriptionText` artık gerçek alan (commit `13382b4`). Yamadaki reflection kullanımı doğrudan atamaya çevrilebilir.

---

## 📋 Yapılacaklar (sırasıyla)

1. **Yama 01'i bitir.** `tools/test-core.sh` ile test et:
   - optik alanlar iki bayt sırasında da çalışmalı (II/MM),
   - `scrubInPlace` için testler yazılmalı.
2. **JPEG**, "marka izlerini temizle" açıkken:
   - Huawei `APP7–APP10 "HUAWEI\0"` segmentleri silinecek.
   - Samsung `APP4/5 "ssuniqueid"` ve Qualcomm `APP4 "QTI Debug Metadata"` silinecek.
   - HDR segmentleri **silinmeyecek**: ITUT35, ISO 21496-1, MPF.
   - MPF ikincil görüntülerindeki EXIF `scrubInPlace` ile temizlenecek. Boyut değişmez, ofsetler bozulmaz.
3. **HEIC**, Xiaomi tipi Exif yükü (`offset 10 + FF E1 len "Exif\0\0"`):
   - TIFF APP1 uzunluğuyla sınırlanacak, düzenlemeden sonra uzunluk alanı güncellenecek.
   - Sondaki önizleme EXIF'i `scrubInPlace` ile temizlenecek.
   - Hedef: çıktıda "Xiaomi", "M2012K11AG" ve "POCO" hiç kalmamalı.
4. **Yama 02'yi bitir ve derle** (`./build.sh`). Ardından bağımsız inceleme (Android çökme riskleri, döndürme, Türkçe metinler).
5. **Gerçek dosya testleri.**
   - Kaynak: `pillow_heif-1.8.1.tar.gz` (PyPI) içindeki `tests/images` ve `piexif-1.1.3.zip` içindeki `tests/images`.
   - Kontroller: pikseller aynı, exiftool uyarısı artmıyor, eski marka adı kalmıyor.
6. **Sürüm.** Commit mesajına `[release]` eklenip push edilince GitHub Release'te APK yayınlanır.
7. **İsteğe bağlı / bilinen sınırlamalar:**
   - Hareketli fotoğrafın içine gömülü MP4 metadata'sı (Huawei LIVE, Samsung/Google motion photo) henüz değiştirilmiyor.
   - Samsung HEIC'te `auxC urn:com:samsung:…hdrgainmap` ve `sefd` sondaki veri duruyor. Silinirse HDR veya hareketli fotoğraf bozulur.
   - EXIF çözünürlük alanları yazılıyor ama gerçek piksel sayısı değişmiyor. İstenirse JPEG için yeniden boyutlandırma eklenebilir.
   - "ASUS Smartphone for Snapdragon Insiders" için kamera verisi yok.

---

## 💻 Kendi bilgisayarınızda nasıl devam edilir

### Kodu almak
- `git clone metakamera-git.bundle MetaKamera`
- ya da GitHub'dan `git fetch origin ccr-d99ebc4f-795ier`

### Derlemek (Windows)
1. **WSL2 + Ubuntu 24.04** (önerilen):
   ```bash
   sudo apt install openjdk-21-jdk aapt zipalign apksigner dalvik-exchange android-sdk-platform-23 \
     libimage-exiftool-perl ffmpeg imagemagick webp gpac libheif-examples libheif-plugin-x265 libheif-plugin-aomenc
   cd metakamera-android && tools/test-core.sh && ./build.sh
   ```
   - İlk derlemede Maven Central'dan Robolectric `android-all` API 36 jar'ı (~200 MB) iner.
2. **Android Studio / Android SDK kuruluysa:** `ANDROID_HOME` tanımlıysa `tools/env.sh`, SDK'daki build-tools'u (aapt, d8, zipalign, apksigner) kullanır.
3. **Hiç kurulum yapmadan:** GitHub'a push edin. Actions APK'yı derler; Artifacts'tan indirilir.

### İmzalama anahtarı
- Anahtar repoya konmadı. Bu zip'teki APK, bulut oturumundaki bir anahtarla imzalandı ve o anahtar oturumla birlikte kaybolacak.
- Kendi bilgisayarınızda ilk `./build.sh`, `~/.config/metakamera/metakamera-release.jks` adıyla yeni bir anahtar oluşturur.
- Bu yüzden ilk yeni sürümü kurmadan önce eski uygulamayı silmeniz gerekir.
- GitHub derlemelerinin de aynı anahtarı kullanması için iki repo secret'ı tanımlayın:
  - `METAKAMERA_KEYSTORE_BASE64` (`base64 -w0 metakamera-release.jks`)
  - `METAKAMERA_KEYSTORE_PASSWORD`

### Claude Code ile devam
Proje klasöründe Claude Code'a şunu yazabilirsiniz:
> "DURUM.md'deki 'Yarıda kalan işler' ve 'Yapılacaklar' listesine göre devam et; yamaları uygula, testleri çalıştır, APK'yı derle."

### Kod kuralları
- `src/` kodu Java 8: lambda ve method reference yok, `java.util.function`/`java.time` yok. Sebep: dx desugaring yapmaz; `test-core.sh` bunu zaten kontrol ediyor.
- Çekirdek (`core`) `android.*` import etmez.
- View/ViewGroup alt sınıfı yazılmaz.
- XML'de API 23'ten yeni öznitelik kullanılmaz (aapt v1 sınırı).

### Araştırma verisi
- `arastirma/cihaz-veritabani-uretici.py`, `assets/devices.json` dosyasını araştırma JSON'larından üretir.
- Betikteki yollar bulut oturumuna göre yazılmış. Yeniden kullanmak için `arastirma/*.json` dosyalarına yönlendirin.

---

## Mimari (kısa)

```
metakamera-android/
  build.sh, tools/env.sh, tools/test-core.sh   derleme + test
  AndroidManifest.xml, res/, assets/devices.json (357 telefon + kamera modülleri)
  src/com/metakamera/core/        saf Java metadata motoru
     MetadataEditor (giriş noktası), FormatDetector, EditSpec, IccIdentity, MediaInfo, EditReport
     tiff/ (EXIF)  icc/ (ICC)  xmp/  jpeg/  png/  webp/  isobmff/ (HEIF + MP4/MOV)
  src/com/metakamera/app/         Android arayüzü
     MainActivity, DevicePickerDialog, SpecBuilder, GallerySaver, EditedFileProvider, ...
  test/com/metakamera/test/       JVM testleri (exiftool/ffmpeg/heif-enc ile)
```
