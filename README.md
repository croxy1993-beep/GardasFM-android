# Gardaş FM — Android

PC 9.0 arayüzünü (gömülü `assets/index.html`) telefonda çalıştırır. Arayüz internet sitesi değil, APK'nın içindedir.

| Parça | Görev |
|---|---|
| `GfmServer.kt` | PC'deki `/api/*` uçlarının telefon sürümü (127.0.0.1, gizli anahtarlı) |
| `Engine.kt` | Oynatıcı (Media3), sıra, favoriler, son çalınanlar, komutlar |
| `Extractor.kt` | YouTube arama ve ses akışı (NewPipeExtractor) |
| `PlaybackService.kt` | Arka planda çalma + bildirim kontrolleri |
| `assets/index.html` | PC 9.0 arayüzü, logolu açılış animasyonu dahil |

## Derleme
GitHub > Actions > android-apk > Run workflow > Artifacts > GardasFM-apk > app-debug.apk
(Etiket `a1.0.0` atınca APK Release'e de eklenir.)

## Faz durumu
- Faz 1 (bu sürüm): açılış, ana sayfa, arama, çalma, arka planda çalma, favoriler, son çalınanlar, mix kartları
- Faz 2: sözler, çalma listeleri, etiketler · Faz 3: indirme, uyku zamanlayıcı, EQ · Faz 4: Discord girişi, otomatik güncelleme

## Notlar
YouTube sık değişir; çalma bozulursa `app/build.gradle` içindeki NewPipeExtractor sürümünü yükseltin.
