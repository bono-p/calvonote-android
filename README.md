# CalvoNote Mobile 🎙
**Transcription vocale offline pour Android — DevLab 2026**

---

## Structure du projet

```
calvonote_android/
│
├── build.gradle.kts                  ← Config Gradle racine
├── settings.gradle.kts               ← Nom projet + module
├── codemagic.yaml                    ← Build cloud Codemagic
│
├── gradle/
│   └── libs.versions.toml            ← Versions des plugins
│
└── app/
    ├── build.gradle.kts              ← Dépendances + config app
    │
    └── src/main/
        ├── AndroidManifest.xml       ← Permissions + activité
        │
        ├── java/com/devlab/calvonote/
        │   ├── MainActivity.kt       ← Interface + logique UI
        │   └── VoskRecognizer.kt     ← Moteur Vosk + AudioRecord
        │
        ├── res/
        │   ├── layout/
        │   │   └── activity_main.xml ← Interface XML
        │   └── values/
        │       ├── colors.xml
        │       ├── strings.xml
        │       └── themes.xml
        │
        └── assets/
            └── vosk-model-small-fr-0.22/   ← ⚠ À PLACER MANUELLEMENT
                ├── am/
                ├── conf/
                ├── graph/
                └── ivector/
```

---

## ⚠ Étape manuelle : placer le modèle Vosk

Le modèle **n'est pas inclus** dans ce repo (trop lourd pour Git).
Tu dois le placer toi-même dans :

```
app/src/main/assets/vosk-model-small-fr-0.22/
```

Tu as déjà ce dossier sur ton PC depuis CalvoNote Desktop.
Copie-le directement à cet emplacement avant l'upload sur GitHub.

---

## Compilation sur Codemagic

1. Crée un repo GitHub et upload tous ces fichiers
2. Connecte le repo sur [codemagic.io](https://codemagic.io)
3. Lance le build → récupère `app-debug.apk`
4. Installe l'APK sur ton Android

---

*Développé par DevLab avec Claude (Anthropic)*
