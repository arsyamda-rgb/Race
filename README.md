# VeyrionRace

Intro + gacha ras (Elf / Beast / Human / Dragon) untuk Veyrion Roleplay, **tanpa inventory GUI**.
Menu pakai title dan tombol klik di chat. Placeholder PlaceholderAPI: `%veyrion_race%` dan `%veyrion_race_id%`.

## Compile lewat GitHub
1. Upload semua isi folder ini ke repo GitHub (termasuk folder `.github`).
2. Buka tab **Actions** -> workflow **Build VeyrionRace** -> tunggu hijau.
3. Buka run-nya, download artifact **VeyrionRace** (isinya `VeyrionRace.jar`).
4. Taruh jar di `plugins/` server, restart.

Compile lokal: `mvn package` (JDK 21), hasilnya `target/VeyrionRace.jar`.

## Perintah (admin, permission `veyrionrace.admin`, default OP)
- `/veyrionrace get <player>`
- `/veyrionrace set <player> <elf|beast|human|dragon>`
- `/veyrionrace reset <player>`  (intro muncul lagi saat dia join)
- `/veyrionrace start <player>`  (jalankan intro sekarang, buat tes)
- `/veyrionrace reload`
