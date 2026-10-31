# NinjaTV Telegram

Repositório público de distribuição da integração Telegram para NinjaTV.

## Situação

Ainda não há uma extensão instalável publicada aqui. O pacote só será adicionado depois que:

1. O aplicativo auxiliar Android compilar e for testado.
2. A integração TDLib conseguir autenticar a conta do usuário e listar apenas chats acessíveis por essa conta.
3. A ponte local entregar os arquivos com suporte a HTTP Range.
4. A extensão NinjaTV conseguir localizar os vídeos e abrir o fluxo no player nativo.

## Separação dos projetos

- Código-fonte e aplicativo auxiliar: [ninjatv-telegram](https://github.com/Pablopinheiroo/ninjatv-telegram) (privado).
- Arquivos públicos de distribuição: este repositório.

Não coloque neste repositório chaves, códigos de autenticação, sessões do Telegram ou dados privados. Use a integração somente com conteúdo ao qual sua conta tenha acesso e que você tenha autorização para reproduzir.
