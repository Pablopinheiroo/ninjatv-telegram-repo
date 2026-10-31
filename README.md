# TelegramNinja — teste de autenticação TDLib

Repositório de distribuição de uma extensão experimental para NinjaTV/CloudStream.

## Teste atual

A primeira compilação contém somente o grupo **Um Trabalho do Inferno** (`t.me/umtrabalhodoinferno`). A extensão tenta executar a TDLib dentro do próprio carregador de plugins do NinjaTV, sem instalar o aplicativo auxiliar.

O pacote de teste deverá validar, nesta ordem:

1. Carregamento das classes Java e bibliotecas nativas TDLib dentro do `.cs3`.
2. Autenticação da conta Telegram diretamente nas configurações da extensão.
3. Leitura das últimas 100 mensagens do grupo e identificação de vídeos.
4. Exibição dos vídeos como episódios da série de teste.
5. Download do vídeo pelo TDLib e abertura pelo player do NinjaTV.

**Importante:** esta versão é apenas um teste técnico. A reprodução de teste baixa o arquivo para o armazenamento privado antes de abrir o player; ainda não é o streaming por intervalos pretendido para a versão final.

## Instalação no NinjaTV

Depois que a compilação publicar os arquivos `repo.json`, `plugins.json` e `.cs3`, adicione ao NinjaTV somente:

`https://raw.githubusercontent.com/Pablopinheiroo/ninjatv-telegram-repo/main/repo.json`

Instale a extensão de teste. Na configuração da extensão, informe o API ID e API Hash do seu aplicativo em my.telegram.org/apps; depois siga as solicitações de telefone, código e senha 2FA, caso exista.

A sessão e as credenciais são armazenadas criptografadas no armazenamento privado do Android. Não são gravadas no repositório.

## Privacidade e uso

O código-fonte principal continua no repositório privado `ninjatv-telegram`. Este repositório público contém somente o protótipo necessário para distribuir e testar a extensão.

Não publique API Hash, códigos de autenticação, senha 2FA ou arquivos de sessão. Use a integração apenas em grupos dos quais sua conta participa e com conteúdo que você tem autorização para acessar e reproduzir.
