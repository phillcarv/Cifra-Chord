# Cifra Chord 🎸

Aplicativo Android nativo em **Kotlin** com **Jetpack Compose** para músicos de barzinho, pequenos shows e eventos.
O aplicativo ouve a música pelo microfone ou importa arquivos de áudio, conecta-se ao backend de inteligência musical e entrega a letra com a cifra perfeitamente alinhada, transposição de tom em tempo real, rolagem automática e repertório offline.

---

## 🚀 Recursos Principais
- **Nome Comercial:** Cifra Chord
- **Ouvir no Bar:** Gravação de áudio de alta fidelidade pelo microfone.
- **Carregar MP3:** Importação rápida de arquivos do aparelho.
- **Transposição de Tom (+ / - semitom):** Adequação instantânea ao tom vocal do músico.
- **Rolagem Automática (*Auto-Scroll*):** Leitura contínua no palco sem tirar a mão do instrumento.
- **Repertório Offline:** Salve músicas favoritas para usar mesmo sem sinal de internet.
- **Monetização Integrada:** Modelo freemium com 3 músicas gratuitas e opções de plano Pro (R$ 19,90/mês ou R$ 149,90/ano).

---

## 📦 Como Compilar o APK no GitHub Actions

1. Suba este diretório para o repositório `cifra-chord` no GitHub:
```powershell
cd "C:\Users\Phill Carv\.gemini\antigravity\scratch\cifra-chord"
git init
git add .
git commit -m "feat: initial commit - Cifra Chord Android app"
git branch -M main
git remote add origin https://github.com/phillcarv/cifra-chord.git
git push -u origin main
```
2. Acesse a aba **Actions** no seu GitHub. O workflow compilará o arquivo `Cifra-Chord-App-Debug.apk` automaticamente em nuvem!
