# Gardaş FM Android - kurulum

## İmza anahtarı (bir kere yapılır)
APK'nın her sürümde AYNI anahtarla imzalanması gerekir, yoksa yeni sürüm eskisinin üstüne kurulmaz.
Anahtar artık depoda DEĞİL, GitHub'da gizli bilgi olarak durur.

1. Sohbetten indirdiğin `GIZLI_imza_anahtari.txt` dosyasını aç.
2. GitHub'da depo sayfası > **Settings** > **Secrets and variables** > **Actions** > **New repository secret**.
3. Dosyadaki dört bilgiyi birebir ekle: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. `GIZLI_imza_anahtari.txt` dosyasını güvenli bir yere YEDEKLE. Depoya yükleme.
5. Eski depodaki `app/gardas.keystore` dosyası silinmiş olmalı (bu pakette yok).

## Derleme
- `main` dalına her yükleme APK'yı derleyip **Releases**'e koyar. Etiket `v<numara>` ve sürüm kodu aynı numaradır.
- Derleme "KEYSTORE_BASE64 ... eklenmemiş" hatası verirse yukarıdaki adımları yapmadın demektir.
- Gradle sürümü workflow'da 8.10.2'ye sabitli.

## İlk kurulum notu
Anahtar değiştiği için **telefondaki eski Gardaş FM'i bir kere silip** yeni APK'yı kur (favoriler gider).
Bundan sonraki güncellemeler otomatik: uygulama açılınca yeni sürüm varsa soru çıkar.

## Otomatik güncellemeyi denemek
1. Yeni APK'yı kur. 2. Depoya küçük bir değişiklik yükle, Actions bitsin. 3. Uygulamayı kapatıp aç: "Güncelle / Sonra" penceresi çıkmalı.
