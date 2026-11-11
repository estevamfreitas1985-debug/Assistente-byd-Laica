# Laica — Assistente de voz para carros BYD

Assistente de voz para a multimídia **DiLink** dos carros BYD (testado no **BYD Seal**, Android 10), usando o **Gemini Live** do Google. Você conversa de forma natural, como com uma pessoa, e ela também comanda funções do carro.

> Projeto independente, sem ligação com a BYD ou com o Google. Use por sua conta e risco e nunca tire a atenção da estrada.

## O que ela faz

- **Conversa natural** em português, com pesquisa no Google (notícias, clima, placares, preços).
- **Chamada por voz** ("Ei Laica"), reconhecida dentro da central, sem internet.
- **Carro:** ar-condicionado (liga/desliga, temperatura, ventilação), vidros e aquecimento/ventilação dos bancos. Portas, travas, carregamento e direção ficam de fora de propósito.
- **Navegação:** abre a rota no Waze ou no Google Maps da central.
- **Música:** toca no Spotify de fábrica da central (pelo serviço de mídia) e controla play/pausa/próxima e volume.
- **Anotações e lembretes:** "anota que estacionei no piso 3", "me lembra daqui a 20 minutos de…", "terça às 9h…".
- **Memória da viagem:** enquanto o carro está ligado, ela continua a mesma conversa entre uma chamada e outra.
- **Liga junto com o carro** e **se atualiza pela internet**, sem pen drive.

## O que você precisa

1. Multimídia BYD que aceite instalar APK (DiLink desbloqueado).
2. Uma **chave da API do Gemini**: crie em <https://aistudio.google.com/apikey>. O uso do Gemini Live é cobrado pelo Google conforme o seu plano.
3. Para algumas funções (música no Spotify de fábrica, ligar junto com o carro e atualização automática): a **depuração sem fio (ADB)** da central ligada, na porta 5555.

## Instalação

1. Baixe o APK mais recente em **Releases**, na lateral desta página.
2. Instale na central (pen drive + gerenciador de arquivos ou App Manager).
3. Abra o app e libere **microfone**, **bolinha sobre outros apps** e **localização**.
4. Crie um arquivo **chave.txt** com a sua chave do Gemini, coloque no pen drive e toque em **Ler chave do pen drive**.
5. Toque em **Testar acesso ao carro** e confira o Registro.
6. Se a depuração sem fio estiver ligada, toque em **Liberar controle da música**. Quando a central perguntar, marque **Sempre permitir**. Depois, em **Geral**, ligue **Ligar junto com o carro** e toque em **Salvar ajustes**.

### Instruções personalizadas

Você pode dar personalidade à assistente: escreva as instruções num arquivo **instrucoes.txt**, coloque no pen drive e toque em **Ler instruções do pen drive**.

## Privacidade

- A chave do Gemini, as instruções e as anotações ficam **só na central do carro**. Nada disso vai para este repositório.
- A chamada por voz é reconhecida **dentro da central**. O áudio só vai para o Gemini depois que você chama a assistente.

## Compilar a sua própria versão

Cada envio para a branch `main` gera um APK novo em **Releases**, pelo GitHub Actions.

- **A chave de assinatura nunca fica no repositório.** Para poder atualizar o app sem desinstalar, crie dois segredos em *Settings → Secrets and variables → Actions*: `KEYSTORE_B64` (o arquivo `.p12` em base64, alias `laica`) e `KEYSTORE_PASS` (a senha dele).
- Sem esses segredos, cada versão sai com uma chave temporária. Nesse caso, desinstale a versão anterior antes de instalar a nova.

## Créditos

- Reconhecimento de voz offline: [Vosk](https://alphacephei.com/vosk/) (modelo pequeno em português).
- Conexão com a depuração da central: [dadb](https://github.com/mobile-dev-inc/dadb).
- Licença: MIT.
