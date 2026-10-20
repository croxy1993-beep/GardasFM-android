# Gardaş FM - Android 2.0

PC 5.0'ın telefon uyarlaması: neon cam arayüz, 9 tema, indirme (internetsiz dinleme), çoklu çalma listeleri,
YouTube listesi içe aktarma, sıradakiler, akıllı sürüm seçimi, ruh haline göre mix, etiketler, EQ/hız,
ve OTOMATİK GÜNCELLEME.

## İlk kurulum (bir kere)
1. Telefondaki ESKİ Gardaş FM'i sil (imza anahtarı değişti, üstüne kurulmaz). Favoriler silinir.
2. Bu klasörün İÇİNDEKİ her şeyi GitHub deposuna yükle (app/gardas.keystore ve .github dahil).
3. Actions > "APK derle" bitince Releases > "Gardaş FM vN" > GardasFM.apk indir ve kur.

## Sonraki sürümler
Dosyaları depoya yükle (commit). Actions yeni sürümü derler ve Release olarak yayınlar (v13, v14...).
Telefondaki uygulama açılışta kendiliğinden yeni sürümü bulur: "Güncelle" tuşuna basınca indirir ve kurulum ekranını açar.
İlk güncellemede Android "Bu kaynaktan yüklemeye izin ver" diye sorar, bir kere izin vermen yeter.

## Önemli
- Depo PUBLIC (herkese açık) olmalı, yoksa uygulama sürümü göremez.
- Depo adı Updater.kt içindeki REPO sabitiyle aynı olmalı (croxy1993-beep/GardasFM2).
- app/gardas.keystore imza anahtarıdır, silme/değiştirme: değişirse güncelleme kurulmaz.
