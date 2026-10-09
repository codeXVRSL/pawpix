package com.pawpixel.i18n

/** Brazilian Portuguese for Lost and Found, Pals and moments, walks, the monthly challenge, the weather, occasions, units and the Care panel. */
internal object PtScreens {
    val map: Map<String, String> = mapOf(
        // Lost and Found
        "Is {0} missing?" to "{0} sumiu?",
        "Raise an alert: PawPixel owners within {1} see {0}'s photos and pixel twin on their map and can report where they saw {0}. Nobody sees your name or your home." to
            "Crie um alerta: donos com PawPixel num raio de {1} veem as fotos e o gêmeo pixel de {0} no mapa e podem avisar onde viram {0}. Ninguém vê seu nome nem sua casa.",
        "This version of the app isn't connected to PawPixel's server yet, so alerts to owners nearby aren't on. You can still share a notice." to
            "Esta versão do app ainda não está conectada ao servidor do PawPixel, então os alertas para donos próximos não estão ativos. Você ainda pode compartilhar um aviso.",
        "What to look for" to "Como reconhecer",
        "Colour, size, collar, how they answer to their name" to "Cor, tamanho, coleira, como responde ao nome",
        "When were they last seen?" to "Quando foi visto pela última vez?",
        "Just now" to "Agora mesmo", "Earlier today" to "Hoje mais cedo", "A few days ago" to "Há alguns dias",
        "Where were they last seen?" to "Onde foi visto pela última vez?",
        "Tap the map to move the marker. This spot is shown to owners nearby." to "Toque no mapa para mover o marcador. Este ponto é mostrado aos donos próximos.",
        "Locating…" to "Localizando…", "Use my location" to "Usar minha localização",
        "Couldn't get your approximate location." to "Não foi possível obter sua localização aproximada.",
        "Photos to show (up to {0})" to "Fotos para mostrar (até {0})", "Photo chosen" to "Foto escolhida", "Choose photo" to "Escolher foto",
        "No photos in {0}'s album yet. The pixel twin is shown instead; add photos to the album any time." to
            "Ainda não há fotos no álbum de {0}. O gêmeo pixel é mostrado no lugar; adicione fotos ao álbum quando quiser.",
        "just now" to "agora mesmo", "earlier today" to "hoje mais cedo", "yesterday" to "ontem", "a few days ago" to "há alguns dias",
        "Sending…" to "Enviando…", "Alert owners nearby" to "Alertar donos próximos",
        "Something went wrong. Please try again." to "Algo deu errado. Tente de novo.",
        "Share a notice" to "Compartilhar um aviso",
        "Tip: tell your barangay, nearby vets and shelters too, and post the notice in local groups." to
            "Dica: avise também os vizinhos, as clínicas veterinárias e os abrigos próximos, e publique o aviso em grupos locais.",
        "dog" to "cachorro", "cat" to "gato", "pet" to "pet",
        "LOST {0}: {1}." to "{0} PERDIDO: {1}.", "Last seen {0}." to "Visto pela última vez {0}.", "Where: {0}" to "Onde: {0}",
        "Seen {0}? Report a sighting here: {1}" to "Viu {0}? Avise aqui: {1}",
        "{0} min ago" to "há {0} min", "{0} h ago" to "há {0} h", "{0} days ago" to "há {0} dias",
        "Welcome home, {0}!" to "Bem-vindo de volta, {0}!",
        "The alert is closed. Thank you to everyone who looked." to "O alerta foi encerrado. Obrigado a todos que procuraram.",
        "Back to {0}'s room" to "Voltar ao quarto de {0}", "Pixel {0}" to "{0} pixel",
        "Alert is on for {0}" to "Alerta ativo para {0}", "Loading…" to "Carregando…",
        "Since {0} · last seen {1} · {2} photos" to "Desde {0} · visto pela última vez {1} · {2} fotos",
        "Share the alert" to "Compartilhar o alerta", "recently" to "há pouco",
        "Sightings" to "Avistamentos", "Sightings ({0})" to "Avistamentos ({0})",
        "None yet. Owners nearby see the alert on their map; sightings show up here. Pull this page open again to check." to
            "Nenhum ainda. Os donos próximos veem o alerta no mapa; os avistamentos aparecem aqui. Abra esta página de novo para conferir.",
        "Check again" to "Conferir de novo", "Remove the alert" to "Remover o alerta",
        "Is {0} safe at home?" to "{0} já está em casa em segurança?", "Remove the alert?" to "Remover o alerta?",
        "The alert closes and leaves everyone's map. The share link says {0} was found." to "O alerta é encerrado e sai do mapa de todos. O link compartilhado dirá que {0} foi encontrado.",
        "The alert and its sightings are deleted. Use this if it was raised by mistake." to "O alerta e seus avistamentos são apagados. Use isto se foi criado por engano.",
        "Yes, safe home" to "Sim, em casa em segurança",
        "{0} · {1} from where they were last seen" to "{0} · a {1} de onde foi visto pela última vez",
        "Sighting photo" to "Foto do avistamento", "Open in maps" to "Abrir no mapa", "LOST: {0}" to "PERDIDO: {0}",
        "Last seen {0} · {1} from your area" to "Visto pela última vez {0} · a {1} da sua área",
        "Photo {0} of {1}" to "Foto {0} de {1}", "{0} sightings reported" to "{0} avistamentos relatados",
        "Thank you. The owner sees your sighting right away." to "Obrigado. O dono vê seu avistamento na hora.",
        "You saw {0}?" to "Você viu {0}?",
        "Your approximate location is sent as the spot, so the owner knows where to look. Add how to reach you if you'd like a call." to
            "Sua localização aproximada é enviada como o ponto, para o dono saber onde procurar. Diga como falar com você se quiser receber uma ligação.",
        "What you saw, when, how to reach you (optional)" to "O que viu, quando, como falar com você (opcional)",
        "Add a photo" to "Adicionar uma foto", "Photo added" to "Foto adicionada", "Send sighting" to "Enviar avistamento",
        "Pets reported missing near you. Tap one to see the photos and report a sighting. Your own pet goes missing? Open their page and tap Lost." to
            "Pets dados como perdidos perto de você. Toque em um para ver as fotos e avisar se o viu. Seu pet sumiu? Abra a página dele e toque em Perdido.",
        "No lost pets reported near you. Good." to "Nenhum pet perdido perto de você. Que bom.",
        "Lost pet: {0}" to "Pet perdido: {0}", "Yours" to "Seu", "Last seen {0} · {1} away" to "Visto pela última vez {0} · a {1}",

        // Pals and moments
        "Pals (demo)" to "Amigos (demo)", "Pals are coming soon" to "Amigos em breve",
        "A small circle of friends whose pixel pets visit your pet's room. This version of the app isn't connected to PawPixel's server yet." to
            "Um círculo pequeno de amigos cujos pets pixel visitam o quarto do seu pet. Esta versão do app ainda não está conectada ao servidor do PawPixel.",
        "Up to {0} friends, by code only. Pals see your pixel pets and their names, and only the moments you choose to share: never your place or your care. Their pets drop by your room; send theirs a treat." to
            "Até {0} amigos, só por código. Os amigos veem seus pets pixel e os nomes deles, e só os momentos que você escolhe compartilhar: nunca seu lugar nem seus cuidados. Os pets deles passam pelo seu quarto; mande um petisco para os deles.",
        "Your pal code" to "Seu código de amigo", "Pal code {0}" to "Código de amigo {0}",
        "Give it to a friend with PawPixel. It never expires; unpal anyone any time." to "Dê a um amigo com PawPixel. Nunca expira; desfaça a amizade quando quiser.",
        "Be my pal on PawPixel: open More → Pals and enter my code {0}. Your pixel pet will visit mine!" to
            "Seja meu amigo no PawPixel: abra Mais → Amigos e digite meu código {0}. Seu pet pixel vai visitar o meu!",
        "Add a pal" to "Adicionar um amigo", "Their code" to "Código dele", "Add" to "Adicionar",
        "You're pals now." to "Agora vocês são amigos.", "You're pals" to "Agora vocês são amigos",
        "Moments" to "Momentos", "Your pals" to "Seus amigos", "Your pals ({0})" to "Seus amigos ({0})",
        "No pals yet. Share your code with one friend: their pixel pet will be on your rug tomorrow." to
            "Nenhum amigo ainda. Compartilhe seu código com um amigo: o pet pixel dele estará no seu tapete amanhã.",
        "Shared with your pals for two days." to "Compartilhado com seus amigos por dois dias.", "Sent to {0}!" to "Enviado para {0}!",
        "A photo of the day for your pals and nobody else. It's gone after two days; no likes, no comments." to
            "Uma foto do dia para seus amigos e mais ninguém. Some depois de dois dias; sem curtidas, sem comentários.",
        "From your pals in the last two days. Yours is gone after two days; no likes, no comments." to
            "Dos seus amigos nos últimos dois dias. A sua some depois de dois dias; sem curtidas, sem comentários.",
        "Your moment: {0}" to "Seu momento: {0}", "Take it down" to "Tirar", "Share another" to "Compartilhar outro", "Share a moment" to "Compartilhar",
        "{0}'s moment: {1}" to "Momento de {0}: {1}", "A pet" to "Um pet", "The photo to share" to "A foto para compartilhar",
        "Pick a photo" to "Escolher uma foto", "Another photo" to "Outra foto", "Or from {0}'s album" to "Ou do álbum de {0}",
        "Album photo" to "Foto do álbum", "Caption (optional)" to "Legenda (opcional)",
        "Only your pals see it, for two days. The photo is shrunk on your phone first; its location data is dropped." to
            "Só seus amigos veem, por dois dias. A foto é reduzida no seu celular antes; os dados de localização são removidos.",
        "A pal (no pets shared yet)" to "Um amigo (ainda sem pets compartilhados)", "{0}'s owner" to "Dono de {0}", "Unpal" to "Desfazer amizade",
        "Treat" to "Petisco", "A pal" to "Um amigo", "Send {0} something" to "Mandar algo para {0}",
        "A treat" to "Um petisco", "A pat" to "Um carinho", "A ball" to "Uma bolinha", "From" to "De",
        "It shows up in {0}'s room as a speech bubble." to "Aparece no quarto de {0} como um balão de fala.", "Send" to "Enviar",
        "{0} sent {1} a pat!" to "{0} mandou um carinho para {1}!", "{0} sent {1} a ball!" to "{0} mandou uma bolinha para {1}!", "{0} sent {1} a treat!" to "{0} mandou um petisco para {1}!",
        "{0}, a pal's pet, is visiting" to "{0}, pet de um amigo, está de visita", "{0} is visiting" to "{0} está de visita",

        // Walks
        "Walk with {0}" to "Passeio com {0}", "{0} trotting along" to "{0} trotando", "{0} minutes" to "{0} minutos",
        "This phone has no step counter; the walk is timed." to "Este celular não conta passos; o passeio é cronometrado.",
        "Counting steps…" to "Contando passos…", "{0} steps · about {1}" to "{0} passos · cerca de {1}",
        "Keep the app open. Steps stay on your phone; no location is used." to "Mantenha o app aberto. Os passos ficam no seu celular; a localização não é usada.",
        "Saving…" to "Salvando…",
        "None yet. Time one and {0} trots along." to "Nenhum ainda. Cronometre um e {0} trota junto.",
        "1 walk · {0} min · about {1}" to "1 passeio · {0} min · cerca de {1}", "1 walk · {0} min" to "1 passeio · {0} min",
        "{0} walks · {1} min · about {2}" to "{0} passeios · {1} min · cerca de {2}", "{0} walks · {1} min" to "{0} passeios · {1} min",

        // Progress and the monthly challenge
        "You've looked after {0} on {1} different days." to "Você cuidou de {0} em {1} dias diferentes.", "That's a lot of love." to "Isso é muito amor.",
        "Share the card" to "Compartilhar o cartão",
        "1 day of care so far · {0} to go to {1}" to "1 dia de cuidados até agora · faltam {0} para {1}",
        "{0} days of care so far · {1} to go to {2}" to "{0} dias de cuidados até agora · faltam {1} para {2}",
        "{0} earns pixel outfits with days of care. Nothing to buy." to "{0} ganha roupinhas pixel com dias de cuidados. Nada para comprar.",
        "None" to "Nenhuma", "{0} · in {1} days" to "{0} · em {1} dias",
        "{0} challenge: {1}. {2}." to "Desafio de {0}: {1}. {2}.", "{0} challenge" to "Desafio de {0}",
        "Done!" to "Feito!", "Last day" to "Último dia", "{0} days left" to "Faltam {0} dias",
        "{0} did it. Same time next month!" to "{0} conseguiu. Mês que vem tem mais!",
        "Log care on {0} days in {1}" to "Registre cuidados em {0} dias em {1}",
        "Take {0} walks with the app in {1}" to "Faça {0} passeios com o app em {1}",
        "Walk {0} together in {1}" to "Caminhem {0} juntos em {1}",
        "Add {0} photos to the album in {1}" to "Adicione {0} fotos ao álbum em {1}",
        "{0} of {1} days" to "{0} de {1} dias", "{0} of {1} walks" to "{0} de {1} passeios", "{0} of {1}" to "{0} de {1}", "{0} of {1} photos" to "{0} de {1} fotos",
        "Fresh start" to "Novo começo", "Show the love" to "Mostre o amor", "Spring in your step" to "Passo animado", "Every day counts" to "Cada dia conta",
        "Miles of smiles" to "Quilômetros de sorrisos", "Summer snapshots" to "Cliques de verão", "Steady as we go" to "Passo firme", "Walkies month" to "Mês dos passeios",
        "Long walk home" to "Longo caminho de volta", "Spooky streak" to "Maratona assombrada", "Thankful snaps" to "Cliques de gratidão", "Winter walkies" to "Passeios de inverno",
        "January" to "janeiro", "February" to "fevereiro", "March" to "março", "April" to "abril", "May" to "maio", "June" to "junho",
        "July" to "julho", "August" to "agosto", "September" to "setembro", "October" to "outubro", "November" to "novembro", "December" to "dezembro",

        // Weekdays (the week dots)
        "Mon" to "seg", "Tue" to "ter", "Wed" to "qua", "Thu" to "qui", "Fri" to "sex", "Sat" to "sáb", "Sun" to "dom",

        // Units
        "miles & lb" to "milhas e lb", "miles & kg" to "milhas e kg", "km & kg" to "km e kg",
        "{0} mi" to "{0} mi", "{0} km" to "{0} km", "{0} miles" to "{0} milhas",

        // The Care panel and the pet page
        "No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care." to
            "Ainda não há tarefas de cuidado. Adicione comida, passeios ou remédio para que o humor de {0} acompanhe os cuidados de verdade.",
        "Shared with {0}" to "Compartilhado com {0}", "Cared for with {0}" to "Cuidado junto com {0}",
        "Reminders for {0}?" to "Lembretes para {0}?",
        "A gentle nudge when it's time for these, only for the tasks you set. Change them any time." to
            "Um toque suave quando for a hora, só para as tarefas que você definir. Mude quando quiser.",
        "Turn on reminders" to "Ativar lembretes",
        "Fed by {0} · {1}" to "Alimentado por {0} · {1}", "Water refilled by {0} · {1}" to "Água trocada por {0} · {1}", "Walked by {0} · {1}" to "Passeou com {0} · {1}",
        "Playtime with {0} · {1}" to "Brincou com {0} · {1}", "Medicine given by {0} · {1}" to "Remédio dado por {0} · {1}", "Groomed by {0} · {1}" to "Escovado por {0} · {1}",
        "Litter cleaned by {0} · {1}" to "Caixa de areia limpa por {0} · {1}", "Done by {0} · {1}" to "Feito por {0} · {1}",
        "Undo {0} for {1}" to "Desfazer {0} de {1}", "Edit {0}" to "Editar {0}", "Mark {0} done for {1}" to "Marcar {0} como feito para {1}",
        "Adjusted to your routine" to "Ajustado à sua rotina", "Edit pet" to "Editar pet", "Delete {0}" to "Excluir {0}", "Delete {0}?" to "Excluir {0}?",
        "This removes the sprite, tasks and history from this phone. It can't be undone." to "Isso apaga o sprite, as tarefas e o histórico deste celular. Não dá para desfazer.",
        "Your household keeps their copy of {0}, no longer shared." to "Sua casa mantém a cópia de {0}, que deixa de ser compartilhada.",
        "Switch pet" to "Trocar de pet", "{0}: all done today" to "{0}: tudo feito hoje", "{0} done" to "{0} feito",
        "In loving memory" to "Em memória", "{0} · {1} photos" to "{0} · {1} fotos",
        "Extra treats today. The room is decorated for it." to "Petiscos extras hoje. O quarto está enfeitado para a ocasião.",
        "Weather outside: {0}" to "Tempo lá fora: {0}", "Reported lost" to "Dado como perdido",
        "Owners nearby are looking. Sightings show in the alert." to "Os donos próximos estão procurando. Os avistamentos aparecem no alerta.", "Alert" to "Alerta",

        // Weather
        "thunder" to "trovoada", "snow" to "neve", "rain" to "chuva", "fog" to "neblina", "cloudy" to "nublado", "sunny" to "ensolarado", "clear night" to "noite limpa",
        "Thunder outside. {0} may want to hide: stay close and keep the doors shut." to "Trovoada lá fora. {0} pode querer se esconder: fique por perto e mantenha as portas fechadas.",
        "{0}° out: the pavement burns paws. Walk {1} early or after sunset, and bring water." to "{0}° lá fora: o asfalto queima as patas. Passeie com {1} cedo ou depois do pôr do sol, e leve água.",
        "{0}° out. Keep {1} in the shade with fresh water." to "{0}° lá fora. Mantenha {1} na sombra com água fresca.",
        "It feels like {0}° today. Water and shade for {1}, and no midday walks." to "A sensação hoje é de {0}°. Água e sombra para {1}, e nada de passeio ao meio-dia.",
        "Snow! Short trips out for {0}, and dry those paws after." to "Neve! Saídas curtas para {0}, e seque as patas depois.",
        "Rain out there. A short walk, then a towel for {0}." to "Está chovendo. Um passeio curto e depois uma toalha para {0}.",
        "Rain today. A window-watching day for {0}." to "Chuva hoje. Um dia de olhar pela janela para {0}.",
        "Chilly out. {0} might like a warm spot (or a sweater) today." to "Está frio. {0} ia gostar de um cantinho quente (ou de uma roupinha) hoje.",
        "Lovely out. Perfect walk weather for {0}." to "Dia lindo. Tempo perfeito para passear com {0}.",

        // Occasions
        "{0}'s first birthday" to "Primeiro aniversário de {0}", "{0} turns {1}" to "{0} faz {1} anos",
        "One year since {0} came home" to "Um ano desde que {0} chegou em casa", "{0} years since {1} came home" to "{0} anos desde que {1} chegou em casa",
        "{0} years together!" to "{0} anos juntos!", "One year together!" to "Um ano juntos!",
        // Rabbits
        "Rabbit" to "Coelho", "rabbit" to "coelho",
        "Myxo-RHD vaccine" to "Vacina Myxo-RHD", "RHDV2 vaccine" to "Vacina RHDV2", "RHD vaccine" to "Vacina RHD",
        "It feels like {0}° out. Rabbits overheat easily: keep {1} somewhere cool and shady, with fresh water." to
            "Sensação de {0}° lá fora. Coelhos sofrem muito com o calor: deixe {1} num lugar fresco e com sombra, com água fresca.",
    )
}
