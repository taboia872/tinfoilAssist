# TinfoilAssist

Um wrapper WebView Android endurecido para o [Tinfoil Chat](https://chat.tinfoil.sh), com foco em privacidade máxima.

> **Aviso:** Este é um projeto independente, não oficial, não afiliado à Tinfoil Inc.

---

## 🤔 O que é?

O Tinfoil Chat é um serviço de IA com privacidade verificável — suas inferências rodam em enclaves criptográficos. Mas o navegador, por si só, já vaza muita coisa: bateria, orientação do dispositivo, geolocalização, User-Agent, cookies de terceiros, telemetria do WebView...

O **TinfoilAssist** é um app Android minimalista que encapsula o Tinfoil Chat num WebView trancado. Ele não é um navegador — é uma **janela blindada** que só deixa passar o estritamente necessário para o Tinfoil funcionar.

---

## 🛡️ Como protege sua privacidade

### Bloqueio de APIs de hardware
Via injeção de JavaScript no `onPageStarted` e `onPageFinished`, as seguintes APIs são neutralizadas antes de qualquer script do site rodar:

| API | O que faz sem bloqueio | O que o app faz |
|-----|------------------------|-----------------|
| `navigator.getBattery` | Revela nível de bateria e status de carregamento | Retorna `Promise.reject()` |
| `DeviceOrientationEvent` | Acesso ao giroscópio | `undefined` |
| `DeviceMotionEvent` | Acesso ao acelerômetro | `undefined` |
| `navigator.vibrate` | Vibração do dispositivo | Retorna `false` |
| `navigator.connection` | Tipo de conexão (WiFi/4G/etc) | `undefined` |
| `navigator.geolocation` | Localização GPS | Retorna erro `PERMISSION_DENIED` |

### Whitelist de domínios
Todo tráfego de rede passa por `shouldInterceptRequest` e `shouldOverrideUrlLoading`. Apenas estes domínios são permitidos:

- `tinfoil.sh`, `chat.tinfoil.sh`, `clerk.tinfoil.sh`, `verification-center.tinfoil.sh`
- `clerk.accounts.dev`, `clerk.com` — autenticação Clerk (email/senha)
- `tinfoilsh.github.io`
- `cdn.jsdelivr.net` — CDN de assets
- `gstatic.com`, `googleapis.com` — recursos estáticos do Google (fontes, ícones, JS)
- `apple.com` — recursos da Apple
- **Bloqueado:** `google.com`, `accounts.google.com`, `googleusercontent.com` (login Google OAuth), `microsoft.com`, `microsoftonline.com`, `live.com` (login Microsoft), `appleid.apple.com` (login Apple)

Tudo o que não estiver na lista é bloqueado com uma resposta vazia.

### Outros mecanismos

- **User-Agent spoofed**: `Mozilla/5.0 (X11; Linux x86_64)` — esconde que é mobile, evita fingerprinting de dispositivo
- **WebView Metrics Opt-Out**: `<meta-data android:name="android.webkit.WebView.MetricsOptOut" android:value="true" />` desativa telemetria do WebView do Google
- **HTTPS only**: conexões não-HTTPS são bloqueadas por padrão
- **Sandbox de dados**: `setDataDirectorySuffix("tinfoil_chat")` isola os dados do WebView do resto do sistema
- **Sem telemetria própria**: zero analytics, zero SDKs de rastreamento, zero ads
- **Cookies flush em onPause/onPageFinished**: garante que cookies de sessão sejam persistidos

---

## ⚠️ Pontos fracos / limitações

Por ser um WebView, este app herda algumas limitações estruturais:

1. **WebView ≠ navegador completo**: O `WebView` do Android é baseado no Chromium, mas é uma versão controlada pelo Google. Atualizações do WebView podem mudar comportamentos sem aviso.

2. **Injeção de JS é temporária**: O bloqueio de APIs via JavaScript roda a cada `onPageStarted`/`onPageFinished`, mas existe uma janela mínima entre o carregamento da página e a execução do script onde as APIs estariam disponíveis. Um script suficientemente rápido poderia teoricamente ler os valores antes do bloqueio.

3. **Whitelist é estática**: Os domínios são hardcoded no `MainActivity.java`. Se o Tinfoil adicionar um novo CDN ou serviço de autenticação, o app precisa ser atualizado.

4. **Sem Service Worker**: O WebView não suporta Service Workers por padrão, o que pode afetar funcionalidades offline do Tinfoil Chat.

5. **Permissões de microfone**: O app precisa de `RECORD_AUDIO` para o recurso de voz do Tinfoil. Embora só seja concedido sob demanda, a permissão existe no manifest.

6. **User-Agent identifica Linux**: Embora esconda que é mobile, o UA `X11; Linux x86_64` é incomum para acessar um chat de IA, o que pode tecnicamente ser usado para fingerprinting reverso.

---

## 🔧 Como funciona (tecnicamente)

```
┌─────────────────────────────────────┐
│         MainActivity (Activity)     │
│  ┌───────────────────────────────┐  │
│  │     WebView (chat.tinfoil.sh)  │  │
│  │                               │  │
│  │  onPageStarted → HARDENING_JS │  │ ← Bloqueia APIs de hardware
│  │  onPageFinished → HARDENING_JS│  │
│  │                               │  │
│  │  shouldInterceptRequest       │  │ ← Whitelist de domínios
│  │  shouldOverrideUrlLoading     │  │
│  │  onPermissionRequest         │  │ ← Só permite áudio sob demanda
│  └───────────────────────────────┘  │
│                                     │
│  SwipeTouchListener → toggle button │
│  CookieManager.flush() em onPause   │
│  WebStorage + IndexedDB habilitados │
└─────────────────────────────────────┘
```

O app carrega `https://chat.tinfoil.sh/` num WebView em tela cheia, sem barra de título.

### Menu de opções

No canto superior direito há um botão com uma seta (←). Ao tocá-lo, um menu desliza suavemente da direita com efeito de vidro (glassmorphism — fundo translúcido escuro com borda sutil). O menu contém 4 ícones:

| Ícone | Função |
|-------|--------|
| 🔄 **Reload** | Recarrega a página |
| 🔒/🔓 **Restrict** | Liga/desliga a whitelist de domínios (modo restrito = bloqueia domínios não autorizados; irrestrito = permite tudo) |
| 🗑️ **Clear Data** | Apaga cookies, cache, histórico, IndexedDB e LocalStorage. Exige confirmação antes de limpar |
| ℹ️ **About** | Mostra versão, créditos e licença |

O botão de seta some quando o menu está aberto (a seta fica dentro do container, à direita). Tocar nela novamente ou em qualquer opção fecha o menu com animação de recolimento.

Para revelar o botão de seta: deslize de cima para baixo no topo da página (quando o conteúdo estiver no topo). Deslizar de baixo para cima esconde o botão e fecha o menu.

---

## 🏗️ Compilando

O build é feito via GitHub Actions — não é necessário ter o Android SDK instalado localmente. Cada push para `main` dispara o workflow que compila o APK e publica como artifact.

Para compilar manualmente:

```bash
./gradlew assembleDebug
```

Para compilar release (requer keystore):

```bash
./gradlew assembleRelease
```

O APK de debug fica em `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📝 Sobre o desenvolvimento

Este projeto foi feito por **vibe coding** — a maior parte do código foi escrita por IA (com modelos como DeepSeek, Gemini e GLM) através de iteração conversacional. Mas a IA não fez tudo sozinha: cada decisão arquitetural, cada trade-off de segurança, cada correção de build e cada investigação de comportamento exigiu **direção e estratégia humana**. A IA era a mãos no teclado; o humano era o arquiteto.

O processo incluiu análise de projetos similares, engenharia reversa do comportamento de armazenamento do Tinfoil Chat (que se mostrou mais sutil do que parecia), e múltiplas iterações de build-quebrado-debug-push no GitHub Actions.

---

## 🙏 Créditos e projetos base

Este app é um fork adaptado de:

- **[gptassist](https://github.com/woheller69/gptassist)** por [@woheller69](https://github.com/woheller69) — wrapper WebView para ChatGPT com boa base de privacidade
- **[geminiAssist](https://github.com/AcideFluorhydrique/geminiAssist)** por [@AcideFluorhydrique](https://github.com/AcideFluorhydrique) — wrapper WebView para Google Gemini

Ambos inspiraram a estrutura básica do WebView, a whitelist de domínios, o sandbox de dados e o spoofing de User-Agent. O TinfoilAssist adiciona bloqueio de APIs de hardware e endurecimento adicional focado no Tinfoil Chat.

---

## 📜 Licença

GNU General Public License v3.0 (GPL-3.0) — veja [LICENSE](LICENSE).
