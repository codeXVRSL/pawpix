package com.pawpixel.i18n

/** Spanish for Lost and Found, Pals and moments, walks, the monthly challenge, the weather, occasions, units and the Care panel. */
internal object EsScreens {
    val map: Map<String, String> = mapOf(
        // Lost and Found
        "Is {0} missing?" to "¿{0} se perdió?",
        "Raise an alert: PawPixel owners within {1} see {0}'s photos and pixel twin on their map and can report where they saw {0}. Nobody sees your name or your home." to
            "Lanza una alerta: los dueños con PawPixel a menos de {1} ven las fotos y el gemelo pixel de {0} en su mapa y pueden avisar dónde vieron a {0}. Nadie ve tu nombre ni tu casa.",
        "This version of the app isn't connected to PawPixel's server yet, so alerts to owners nearby aren't on. You can still share a notice." to
            "Esta versión de la app aún no está conectada al servidor de PawPixel, así que las alertas a dueños cercanos no están activas. Aun así puedes compartir un aviso.",
        "What to look for" to "Cómo reconocerlo",
        "Colour, size, collar, how they answer to their name" to "Color, tamaño, collar, cómo responde a su nombre",
        "When were they last seen?" to "¿Cuándo lo viste por última vez?",
        "Just now" to "Ahora mismo", "Earlier today" to "Hoy más temprano", "A few days ago" to "Hace unos días",
        "Where were they last seen?" to "¿Dónde lo viste por última vez?",
        "Tap the map to move the marker. This spot is shown to owners nearby." to "Toca el mapa para mover el marcador. Este punto se muestra a los dueños cercanos.",
        "Locating…" to "Ubicando…", "Use my location" to "Usar mi ubicación",
        "Couldn't get your approximate location." to "No se pudo obtener tu ubicación aproximada.",
        "Photos to show (up to {0})" to "Fotos para mostrar (hasta {0})", "Photo chosen" to "Foto elegida", "Choose photo" to "Elegir foto",
        "No photos in {0}'s album yet. The pixel twin is shown instead; add photos to the album any time." to
            "Aún no hay fotos en el álbum de {0}. Se muestra el gemelo pixel; agrega fotos al álbum cuando quieras.",
        "just now" to "ahora mismo", "earlier today" to "hoy más temprano", "yesterday" to "ayer", "a few days ago" to "hace unos días",
        "Sending…" to "Enviando…", "Alert owners nearby" to "Alertar a dueños cercanos",
        "Something went wrong. Please try again." to "Algo salió mal. Inténtalo de nuevo.",
        "Share a notice" to "Compartir un aviso",
        "Tip: tell your barangay, nearby vets and shelters too, and post the notice in local groups." to
            "Consejo: avisa también a tus vecinos, a las veterinarias y refugios cercanos, y publica el aviso en grupos locales.",
        "dog" to "perro", "cat" to "gato", "pet" to "mascota",
        "LOST {0}: {1}." to "{0} PERDIDO: {1}.", "Last seen {0}." to "Visto por última vez {0}.", "Where: {0}" to "Dónde: {0}",
        "Seen {0}? Report a sighting here: {1}" to "¿Viste a {0}? Avisa aquí: {1}",
        "{0} min ago" to "hace {0} min", "{0} h ago" to "hace {0} h", "{0} days ago" to "hace {0} días",
        "Welcome home, {0}!" to "¡Bienvenido a casa, {0}!",
        "The alert is closed. Thank you to everyone who looked." to "La alerta está cerrada. Gracias a todos los que buscaron.",
        "Back to {0}'s room" to "Volver al cuarto de {0}", "Pixel {0}" to "{0} pixel",
        "Alert is on for {0}" to "Alerta activa para {0}", "Loading…" to "Cargando…",
        "Since {0} · last seen {1} · {2} photos" to "Desde {0} · visto por última vez {1} · {2} fotos",
        "Share the alert" to "Compartir la alerta", "recently" to "hace poco",
        "Sightings" to "Avistamientos", "Sightings ({0})" to "Avistamientos ({0})",
        "None yet. Owners nearby see the alert on their map; sightings show up here. Pull this page open again to check." to
            "Ninguno todavía. Los dueños cercanos ven la alerta en su mapa; los avistamientos aparecen aquí. Vuelve a abrir esta página para revisar.",
        "Check again" to "Revisar de nuevo", "Remove the alert" to "Quitar la alerta",
        "Is {0} safe at home?" to "¿{0} ya está a salvo en casa?", "Remove the alert?" to "¿Quitar la alerta?",
        "The alert closes and leaves everyone's map. The share link says {0} was found." to "La alerta se cierra y desaparece del mapa de todos. El enlace compartido dirá que {0} fue encontrado.",
        "The alert and its sightings are deleted. Use this if it was raised by mistake." to "La alerta y sus avistamientos se borran. Úsalo si se lanzó por error.",
        "Yes, safe home" to "Sí, a salvo en casa",
        "{0} · {1} from where they were last seen" to "{0} · a {1} de donde fue visto por última vez",
        "Sighting photo" to "Foto del avistamiento", "Open in maps" to "Abrir en mapas", "LOST: {0}" to "PERDIDO: {0}",
        "Last seen {0} · {1} from your area" to "Visto por última vez {0} · a {1} de tu zona",
        "Photo {0} of {1}" to "Foto {0} de {1}", "{0} sightings reported" to "{0} avistamientos reportados",
        "Thank you. The owner sees your sighting right away." to "Gracias. El dueño ve tu avistamiento de inmediato.",
        "You saw {0}?" to "¿Viste a {0}?",
        "Your approximate location is sent as the spot, so the owner knows where to look. Add how to reach you if you'd like a call." to
            "Se envía tu ubicación aproximada como el punto, para que el dueño sepa dónde buscar. Agrega cómo contactarte si quieres que te llame.",
        "What you saw, when, how to reach you (optional)" to "Qué viste, cuándo, cómo contactarte (opcional)",
        "Add a photo" to "Agregar una foto", "Photo added" to "Foto agregada", "Send sighting" to "Enviar avistamiento",
        "Pets reported missing near you. Tap one to see the photos and report a sighting. Your own pet goes missing? Open their page and tap Lost." to
            "Mascotas reportadas como perdidas cerca de ti. Toca una para ver las fotos y avisar si la viste. ¿Se perdió tu mascota? Abre su página y toca Perdido.",
        "No lost pets reported near you. Good." to "No hay mascotas perdidas reportadas cerca de ti. Qué bien.",
        "Lost pet: {0}" to "Mascota perdida: {0}", "Yours" to "Tuya", "Last seen {0} · {1} away" to "Visto por última vez {0} · a {1}",

        // Pals and moments
        "Pals (demo)" to "Amigos (demo)", "Pals are coming soon" to "Los amigos llegan pronto",
        "A small circle of friends whose pixel pets visit your pet's room. This version of the app isn't connected to PawPixel's server yet." to
            "Un círculo pequeño de amigos cuyas mascotas pixel visitan el cuarto de tu mascota. Esta versión de la app aún no está conectada al servidor de PawPixel.",
        "Up to {0} friends, by code only. Pals see your pixel pets and their names, and only the moments you choose to share: never your place or your care. Their pets drop by your room; send theirs a treat." to
            "Hasta {0} amigos, solo por código. Los amigos ven tus mascotas pixel y sus nombres, y solo los momentos que eliges compartir: nunca tu lugar ni tus cuidados. Sus mascotas pasan por tu cuarto; mándales un premio a las suyas.",
        "Your pal code" to "Tu código de amigo", "Pal code {0}" to "Código de amigo {0}",
        "Give it to a friend with PawPixel. It never expires; unpal anyone any time." to "Dáselo a un amigo con PawPixel. No caduca; puedes dejar de ser amigos cuando quieras.",
        "Be my pal on PawPixel: open More → Pals and enter my code {0}. Your pixel pet will visit mine!" to
            "Sé mi amigo en PawPixel: abre Más → Amigos y escribe mi código {0}. ¡Tu mascota pixel visitará a la mía!",
        "Add a pal" to "Agregar un amigo", "Their code" to "Su código", "Add" to "Agregar",
        "You're pals now." to "Ya son amigos.", "You're pals" to "Ya son amigos",
        "Moments" to "Momentos", "Your pals" to "Tus amigos", "Your pals ({0})" to "Tus amigos ({0})",
        "No pals yet. Share your code with one friend: their pixel pet will be on your rug tomorrow." to
            "Aún no tienes amigos. Comparte tu código con un amigo: su mascota pixel estará en tu alfombra mañana.",
        "Shared with your pals for two days." to "Compartido con tus amigos por dos días.", "Sent to {0}!" to "¡Enviado a {0}!",
        "A photo of the day for your pals and nobody else. It's gone after two days; no likes, no comments." to
            "Una foto del día para tus amigos y nadie más. Desaparece a los dos días; sin likes, sin comentarios.",
        "From your pals in the last two days. Yours is gone after two days; no likes, no comments." to
            "De tus amigos en los últimos dos días. La tuya desaparece a los dos días; sin likes, sin comentarios.",
        "Your moment: {0}" to "Tu momento: {0}", "Take it down" to "Quitarlo", "Share another" to "Compartir otro", "Share a moment" to "Compartir",
        "{0}'s moment: {1}" to "Momento de {0}: {1}", "A pet" to "Una mascota", "The photo to share" to "La foto para compartir",
        "Pick a photo" to "Elegir una foto", "Another photo" to "Otra foto", "Or from {0}'s album" to "O del álbum de {0}",
        "Album photo" to "Foto del álbum", "Caption (optional)" to "Texto (opcional)",
        "Only your pals see it, for two days. The photo is shrunk on your phone first; its location data is dropped." to
            "Solo tus amigos la ven, por dos días. La foto se reduce primero en tu teléfono; se quitan sus datos de ubicación.",
        "A pal (no pets shared yet)" to "Un amigo (aún sin mascotas compartidas)", "{0}'s owner" to "Dueño de {0}", "Unpal" to "Dejar de ser amigos",
        "Treat" to "Premio", "A pal" to "Un amigo", "Send {0} something" to "Enviarle algo a {0}",
        "A treat" to "Un premio", "A pat" to "Una caricia", "A ball" to "Una pelota", "From" to "De",
        "It shows up in {0}'s room as a speech bubble." to "Aparece en el cuarto de {0} como un globo de diálogo.", "Send" to "Enviar",
        "{0} sent {1} a pat!" to "¡{0} le mandó una caricia a {1}!", "{0} sent {1} a ball!" to "¡{0} le mandó una pelota a {1}!", "{0} sent {1} a treat!" to "¡{0} le mandó un premio a {1}!",
        "{0}, a pal's pet, is visiting" to "{0}, la mascota de un amigo, está de visita", "{0} is visiting" to "{0} está de visita",

        // Walks
        "Walk with {0}" to "Paseo con {0}", "{0} trotting along" to "{0} trotando", "{0} minutes" to "{0} minutos",
        "This phone has no step counter; the walk is timed." to "Este teléfono no cuenta pasos; el paseo se cronometra.",
        "Counting steps…" to "Contando pasos…", "{0} steps · about {1}" to "{0} pasos · unos {1}",
        "Keep the app open. Steps stay on your phone; no location is used." to "Mantén la app abierta. Los pasos se quedan en tu teléfono; no se usa la ubicación.",
        "Saving…" to "Guardando…",
        "None yet. Time one and {0} trots along." to "Ninguno todavía. Cronometra uno y {0} trota contigo.",
        "1 walk · {0} min · about {1}" to "1 paseo · {0} min · unos {1}", "1 walk · {0} min" to "1 paseo · {0} min",
        "{0} walks · {1} min · about {2}" to "{0} paseos · {1} min · unos {2}", "{0} walks · {1} min" to "{0} paseos · {1} min",

        // Progress and the monthly challenge
        "You've looked after {0} on {1} different days." to "Has cuidado a {0} durante {1} días distintos.", "That's a lot of love." to "Eso es mucho cariño.",
        "Share the card" to "Compartir la tarjeta",
        "1 day of care so far · {0} to go to {1}" to "1 día de cuidados hasta ahora · faltan {0} para {1}",
        "{0} days of care so far · {1} to go to {2}" to "{0} días de cuidados hasta ahora · faltan {1} para {2}",
        "{0} earns pixel outfits with days of care. Nothing to buy." to "{0} gana atuendos pixel con días de cuidados. Nada que comprar.",
        "None" to "Ninguno", "{0} · in {1} days" to "{0} · en {1} días",
        "{0} challenge: {1}. {2}." to "Reto de {0}: {1}. {2}.", "{0} challenge" to "Reto de {0}",
        "Done!" to "¡Listo!", "Last day" to "Último día", "{0} days left" to "Quedan {0} días",
        "{0} did it. Same time next month!" to "{0} lo logró. ¡El mes que viene, otra vez!",
        "Log care on {0} days in {1}" to "Registra cuidados {0} días en {1}",
        "Take {0} walks with the app in {1}" to "Da {0} paseos con la app en {1}",
        "Walk {0} together in {1}" to "Caminen {0} juntos en {1}",
        "Add {0} photos to the album in {1}" to "Agrega {0} fotos al álbum en {1}",
        "{0} of {1} days" to "{0} de {1} días", "{0} of {1} walks" to "{0} de {1} paseos", "{0} of {1}" to "{0} de {1}", "{0} of {1} photos" to "{0} de {1} fotos",
        "Fresh start" to "Nuevo comienzo", "Show the love" to "Muestra el cariño", "Spring in your step" to "Paso alegre", "Every day counts" to "Cada día cuenta",
        "Miles of smiles" to "Kilómetros de sonrisas", "Summer snapshots" to "Fotos de verano", "Steady as we go" to "Paso firme", "Walkies month" to "Mes de paseos",
        "Long walk home" to "Largo camino a casa", "Spooky streak" to "Racha de miedo", "Thankful snaps" to "Fotos de gratitud", "Winter walkies" to "Paseos de invierno",
        "January" to "enero", "February" to "febrero", "March" to "marzo", "April" to "abril", "May" to "mayo", "June" to "junio",
        "July" to "julio", "August" to "agosto", "September" to "septiembre", "October" to "octubre", "November" to "noviembre", "December" to "diciembre",

        // Weekdays (the week dots)
        "Mon" to "lun", "Tue" to "mar", "Wed" to "mié", "Thu" to "jue", "Fri" to "vie", "Sat" to "sáb", "Sun" to "dom",

        // Units
        "miles & lb" to "millas y lb", "miles & kg" to "millas y kg", "km & kg" to "km y kg",
        "{0} mi" to "{0} mi", "{0} km" to "{0} km", "{0} miles" to "{0} millas",

        // The Care panel and the pet page
        "No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care." to
            "Aún no hay tareas de cuidado. Agrega comida, paseos o medicina para que el ánimo de {0} siga los cuidados reales.",
        "Shared with {0}" to "Compartido con {0}", "Cared for with {0}" to "Cuidado junto con {0}",
        "Reminders for {0}?" to "¿Recordatorios para {0}?",
        "A gentle nudge when it's time for these, only for the tasks you set. Change them any time." to
            "Un aviso suave cuando sea hora, solo para las tareas que tú configures. Cámbialos cuando quieras.",
        "Turn on reminders" to "Activar recordatorios",
        "Fed by {0} · {1}" to "Alimentado por {0} · {1}", "Water refilled by {0} · {1}" to "Agua cambiada por {0} · {1}", "Walked by {0} · {1}" to "Paseado por {0} · {1}",
        "Playtime with {0} · {1}" to "Juego con {0} · {1}", "Medicine given by {0} · {1}" to "Medicina dada por {0} · {1}", "Groomed by {0} · {1}" to "Cepillado por {0} · {1}",
        "Litter cleaned by {0} · {1}" to "Arenero limpiado por {0} · {1}", "Done by {0} · {1}" to "Hecho por {0} · {1}",
        "Undo {0} for {1}" to "Deshacer {0} de {1}", "Edit {0}" to "Editar {0}", "Mark {0} done for {1}" to "Marcar {0} como hecho para {1}",
        "Adjusted to your routine" to "Ajustado a tu rutina", "Edit pet" to "Editar mascota", "Delete {0}" to "Eliminar a {0}", "Delete {0}?" to "¿Eliminar a {0}?",
        "This removes the sprite, tasks and history from this phone. It can't be undone." to "Esto borra el sprite, las tareas y el historial de este teléfono. No se puede deshacer.",
        "Your household keeps their copy of {0}, no longer shared." to "Tu hogar conserva su copia de {0}, ya no compartida.",
        "Switch pet" to "Cambiar de mascota", "{0}: all done today" to "{0}: todo hecho hoy", "{0} done" to "{0} hecho",
        "In loving memory" to "En su memoria", "{0} · {1} photos" to "{0} · {1} fotos",
        "Extra treats today. The room is decorated for it." to "Premios extra hoy. El cuarto está decorado para la ocasión.",
        "Weather outside: {0}" to "Clima afuera: {0}", "Reported lost" to "Reportado como perdido",
        "Owners nearby are looking. Sightings show in the alert." to "Los dueños cercanos están buscando. Los avistamientos aparecen en la alerta.", "Alert" to "Alerta",

        // Weather
        "thunder" to "tormenta", "snow" to "nieve", "rain" to "lluvia", "fog" to "niebla", "cloudy" to "nublado", "sunny" to "soleado", "clear night" to "noche despejada",
        "Thunder outside. {0} may want to hide: stay close and keep the doors shut." to "Hay tormenta. {0} quizá quiera esconderse: quédate cerca y mantén las puertas cerradas.",
        "{0}° out: the pavement burns paws. Walk {1} early or after sunset, and bring water." to "{0}° afuera: el pavimento quema las patas. Pasea a {1} temprano o después del atardecer, y lleva agua.",
        "{0}° out. Keep {1} in the shade with fresh water." to "{0}° afuera. Mantén a {1} a la sombra con agua fresca.",
        "It feels like {0}° today. Water and shade for {1}, and no midday walks." to "Hoy se sienten {0}°. Agua y sombra para {1}, y nada de paseos al mediodía.",
        "Snow! Short trips out for {0}, and dry those paws after." to "¡Nieve! Salidas cortas para {0}, y sécale las patas después.",
        "Rain out there. A short walk, then a towel for {0}." to "Está lloviendo. Un paseo corto y luego una toalla para {0}.",
        "Rain today. A window-watching day for {0}." to "Hoy llueve. Un día de mirar por la ventana para {0}.",
        "Chilly out. {0} might like a warm spot (or a sweater) today." to "Hace frío. A {0} le gustaría un lugar calentito (o un suéter) hoy.",
        "Lovely out. Perfect walk weather for {0}." to "Qué lindo día. Clima perfecto para pasear a {0}.",

        // Occasions
        "{0}'s first birthday" to "Primer cumpleaños de {0}", "{0} turns {1}" to "{0} cumple {1}",
        "One year since {0} came home" to "Un año desde que {0} llegó a casa", "{0} years since {1} came home" to "{0} años desde que {1} llegó a casa",
        "{0} years together!" to "¡{0} años juntos!", "One year together!" to "¡Un año juntos!",
        // Rabbits
        "Rabbit" to "Conejo", "rabbit" to "conejo",
        "Myxo-RHD vaccine" to "Vacuna Myxo-RHD", "RHDV2 vaccine" to "Vacuna RHDV2", "RHD vaccine" to "Vacuna RHD",
        "It feels like {0}° out. Rabbits overheat easily: keep {1} somewhere cool and shady, with fresh water." to
            "Se sienten {0}° afuera. Los conejos sufren mucho el calor: deja a {1} en un lugar fresco y a la sombra, con agua fresca.",
        "These translations are new: tell us if something sounds off." to "Estas traducciones son nuevas: avísanos si algo suena raro.",
    )
}
