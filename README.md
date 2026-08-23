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
Via injeção de JavaScript em `document_start` (`addDocumentStartJavaScript`), as seguintes APIs são neutralizadas **antes de qualquer script do site rodar**:

| API | O que faz sem bloqueio | O que o app faz |
|-----|------------------------|-----------------|
| `navigator.getBattery` | Revela nível de bateria e status de carregamento | Retorna `Promise.reject()` |
| `DeviceOrientationEvent` | Acesso ao giroscópio | `undefined` |
| `DeviceMotionEvent` | Acesso ao acelerômetro | `undefined` |
| `navigator.vibrate` | Vibração do dispositivo | Retorna `false` |
| `navigator.connection` | Tipo de conexão (WiFi/4G/etc) | `undefined` |
| `navigator.geolocation` | Localização GPS | Retorna erro `PERMISSION_DENIED` |
| `navigator.hardwareConcurrency` | Número de núcleos da CPU | Spoofed para `4` |
| `navigator.deviceMemory` | Quantidade de RAM | Spoofed para `4` |
| `WebGLRenderingContext.getParameter` | Vendor e renderer da GPU | Spoofed para Intel UHD 630 |
| `RTCPeerConnection` (WebRTC) | Vaza IP local via STUN/TURN | Bloqueado |
| `navigator.doNotTrack` | Sinal de não-rastreamento | Retorna `'1'` |

### Timezone spoofing (IANA-aware, DST-correct)
O app spooofa o timezone do dispositivo em toda a superfície do `Date` e do `Intl.DateTimeFormat`. Um timezone aleatório é escolhido por sessão entre 25 zonas IANA. O spoof cobre:

- `Date.getTimezoneOffset()`, todos os getters/setters locais (`getHours`, `getMinutes`, etc.)
- `Date.toString()`, `toDateString()`, `toTimeString()`, `toLocale*()`
- `Intl.DateTimeFormat().resolvedOptions().timeZone`
- Construtor `new Date()` com múltiplos argumentos (interpretados como wall-clock na zona spoofada)
- Hook em `HTMLIFrameElement.prototype.contentWindow` cobre vazamentos em iframes sandboxed

### Whitelist de domínios
Todo tráfego de rede passa por `shouldInterceptRequest` e `shouldOverrideUrlLoading`. Apenas estes domínios são permitidos:

- `tinfoil.sh`, `chat.tinfoil.sh`, `api.tinfoil.sh`, `atc.tinfoil.sh`, `clerk.tinfoil.sh`, `verification-center.tinfoil.sh`
- `clerk.accounts.dev`, `clerk.dev`, `clerk.com` — autenticação Clerk (email/senha)
- `tinfoilsh.github.io`
- `cdn.jsdelivr.net` — CDN de assets
- **Bloqueado:** `google.com`, `accounts.google.com`, `googleusercontent.com` (login Google OAuth), `microsoft.com`, `microsoftonline.com`, `live.com` (login Microsoft), `appleid.apple.com` (login Apple), `gstatic.com`, `googleapis.com`, `apple.com` (recursos Google/Apple — não utilizados pelo Tinfoil Chat)

O matching da whitelist é estrito (`host.equals(d) || host.endsWith("." + d)`) — domínios lookalike como `evilclerk.com` não passam. Requisições bloqueadas recebem `403` com corpo JSON vazio.

### Ponte de login (workaround para bug do Clerk)

A página dedicada `chat.tinfoil.sh/signin` tem um bug no Clerk: mesmo com código de verificação de e-mail correto, o fluxo falha com *"no matching user"* tanto para contas novas quanto existentes. O mesmo fluxo funciona pelo modal de login em `tinfoil.sh`.

Como os dois domínios compartilham o cookie de sessão do Clerk, o app faz uma ponte:

1. Toda navegação para `chat.tinfoil.sh/signin` é interceptada (via `onPageStarted` para navegações completas e `doUpdateVisitedHistory` para navegações SPA/pushState)
2. Um diálogo oferece abrir `tinfoil.sh` no próprio WebView
3. Após o login pelo modal, o Clerk redireciona para `dash.tinfoil.sh`
4. O app detecta esse redirect em `onPageFinished` e volta automaticamente para `chat.tinfoil.sh` — já autenticado

### Outros mecanismos

- **User-Agent spoofed**: UA fixo de desktop Chrome em Windows (`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ... Chrome/137.0.0.0 Safari/537.36`) — o UA real mais comum do mundo, esconde que é mobile e não vaza a arquitetura do dispositivo (antes usava `os.arch`, que retornava `aarch64` e criava um fingerprint único e suspeito para provedores de auth)
- **WebView Metrics Opt-Out**: `<meta-data android:name="android.webkit.WebView.MetricsOptOut" android:value="true" />` desativa telemetria do WebView do Google
- **HTTPS only**: conexões não-HTTPS são bloqueadas por padrão
- **Sandbox de dados**: `setDataDirectorySuffix("tinfoil_chat")` isola os dados do WebView do resto do sistema
- **Sem telemetria própria**: zero analytics, zero SDKs de rastreamento, zero ads
- **Cookies flush em onPause/onPageFinished**: garante que cookies de sessão sejam persistidos
- **Settings persistentes**: as opções de privacidade (restrição de domínios, WebRTC, sensores, DNT, timezone spoof) são salvas em SharedPreferences e sobrevivem a reinícios do app
- **Assinatura fixa entre builds**: os APKs do CI são assinados com um keystore persistente (guardado em GitHub Secrets), então **atualizações instalam por cima sem precisar desinstalar** a versão anterior
- **Sem backup no Google Drive**: `android:allowBackup="false"` — cookies de sessão nunca saem do dispositivo via backup
- **Cleartext bloqueado via networkSecurityConfig**: `network_security_config.xml` com `cleartextTrafficPermitted="false"` app-wide

---

## ⚠️ Pontos fracos / limitações

Por ser um WebView, este app herda algumas limitações estruturais:

1. **WebView ≠ navegador completo**: O `WebView` do Android é baseado no Chromium, mas é uma versão controlada pelo Google. Atualizações do WebView podem mudar comportamentos sem aviso.

2. **Injeção em document_start**: Os scripts de hardening são injetados via `addDocumentStartJavaScript` (androidx.webkit), que roda **antes** de qualquer script da página. Isso elimina a janela de exposição que existia na abordagem anterior (`onPageStarted`/`onPageFinished`). Fallback: em WebViews muito antigos sem suporte a `DOCUMENT_START_SCRIPT`, os scripts são injetados em `onPageStarted` via `evaluateJavascript()`.

3. **Whitelist é estática**: Os domínios são hardcoded no `MainActivity.java`. Se o Tinfoil adicionar um novo CDN ou serviço de autenticação, o app precisa ser atualizado.

4. **Sem Service Worker**: O WebView não suporta Service Workers por padrão, o que pode afetar funcionalidades offline do Tinfoil Chat.

5. **Permissões de microfone**: O app precisa de `RECORD_AUDIO` para o recurso de voz do Tinfoil. Embora só seja concedido sob demanda, a permissão existe no manifest.

6. **User-Agent identifica Linux**: Embora esconda que é mobile, o UA `X11; Linux x86_64` é incomum para acessar um chat de IA, o que pode tecnicamente ser usado para fingerprinting reverso.

---

## 🔧 Como funciona (tecnicamente)

```
┌──────────────────────────────────────────┐
│         MainActivity (Activity)          │
│  ┌────────────────────────────────────┐  │
│  │     WebView (chat.tinfoil.sh)      │  │
│  │                                    │  │
│  │  addDocumentStartJavaScript:       │  │ ← Injeção em document_start
│  │    ├─ hardening.js                 │  │   (antes de qualquer script
│  │    │   (battery, sensors, WebGL,    │  │    da página)
│  │    │    WebRTC, CPU/RAM, DNT,       │  │
│  │    │    iframe.contentWindow hook) │  │
│  │    └─ tzspoof.js                   │  │
│  │        (Date, Intl.DateTimeFormat, │  │
│  │         timezone aleatório IANA)  │  │
│  │                                    │  │
│  │  shouldInterceptRequest             │  │ ← Whitelist de domínios
│  │  shouldOverrideUrlLoading           │  │
│  │  onPermissionRequest               │  │ ← Só permite áudio sob demanda
│  └────────────────────────────────────┘  │
│                                          │
│  SwipeTouchListener → toggle button      │
│  CookieManager.flush() em onPause        │
│  WebStorage + IndexedDB habilitados      │
└──────────────────────────────────────────┘
```

O app carrega `https://chat.tinfoil.sh/` num WebView em tela cheia, sem barra de título.

### Menu de opções

No canto superior direito há um botão com uma seta (←). Ao tocá-lo, o container inteiro desliza suavemente da direita para a esquerda (500ms), revelando os ícones junto com o recipiente — sem fade-in/fade-out dos ícones. O menu tem efeito de vidro (glassmorphism — fundo translúcido escuro com borda sutil) e contém 4 ícones:

| Ícone | Função |
|-------|--------|
| 🔄 **Reload** | Recarrega a página |
| 🗑️ **Clear Data** | Apaga cookies, cache, histórico, IndexedDB e LocalStorage. Exige confirmação antes de limpar |
| ⚙️ **Settings** | Dialog com toggles: Restrict domains (HTTPS + OAuth block), Block WebRTC, Block sensors, DNT, Spoof timezone |
| ℹ️ **About** | Mostra versão, features e créditos |

O botão de seta permanece sempre visível. Quando o menu está fechado, só a seta aparece. Ao tocar nela, o container desliza para a esquerda revelando os botões. Ao tocar novamente (ou em qualquer opção), o container desliza de volta para a direita até só a seta ficar visível — sem sumir completamente da tela.

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

- **[testAssist](https://github.com/taboia872/testAssist)** — laboratório de anti-fingerprinting de onde o `tzspoof.js`, `hardening.js` e o sistema de injeção `addDocumentStartJavaScript` foram portados
- **[notme](https://github.com/deafenken/notme)** — implementação de referência para timezone spoofing IANA-aware e anti-fingerprinting
- **[gptassist](https://github.com/woheller69/gptassist)** por [@woheller69](https://github.com/woheller69) — wrapper WebView para ChatGPT com boa base de privacidade
- **[geminiAssist](https://github.com/AcideFluorhydrique/geminiAssist)** por [@AcideFluorhydrique](https://github.com/AcideFluorhydrique) — wrapper WebView para Google Gemini

Ambos inspiraram a estrutura básica do WebView, a whitelist de domínios, o sandbox de dados e o spoofing de User-Agent. O TinfoilAssist adiciona bloqueio de APIs de hardware, spoofing de timezone e fingerprint, e endurecimento adicional focado no Tinfoil Chat.

---

## 📜 Licença

GNU General Public License v3.0 (GPL-3.0) — veja [LICENSE](LICENSE).
