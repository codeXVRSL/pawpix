package com.pawpixel.i18n

/**
 * Spanish: the words the owner sees every day (the room, care, moods, reminders, the maker, the
 * main buttons). Screens not covered yet show English; add to this table as they're translated.
 */
internal object EsCore {
    val map: Map<String, String> = mapOf(
        // Tasks
        "Feed" to "Comida", "Fresh water" to "Agua fresca", "Walk" to "Paseo", "Play" to "Juego", "Medicine" to "Medicina",
        "Groom" to "Cepillado", "Clean litter" to "Limpiar arenero", "Vaccine" to "Vacuna", "Deworming" to "Desparasitación",
        "Tick & flea" to "Garrapatas y pulgas", "Vet check-up" to "Revisión veterinaria", "Anti-rabies shot" to "Vacuna antirrábica",
        "Tick & flea prevention" to "Prevención de garrapatas y pulgas", "Heartworm prevention" to "Prevención del gusano del corazón",
        "5-in-1 vaccine" to "Vacuna 5 en 1", "FVRCP vaccine" to "Vacuna FVRCP", "Rabies vaccine" to "Vacuna antirrábica",
        "DHPP vaccine" to "Vacuna DHPP", "DAPP vaccine" to "Vacuna DAPP", "DHP vaccine" to "Vacuna DHP", "C5 vaccine" to "Vacuna C5", "F3 vaccine" to "Vacuna F3",
        "FeLV vaccine" to "Vacuna FeLV", "Leptospirosis vaccine" to "Vacuna contra la leptospirosis", "Flea & tick prevention" to "Prevención de pulgas y garrapatas",
        "Fed" to "Comió", "Refilled water" to "Agua rellenada", "Walked" to "Paseó", "Played" to "Jugó", "Gave medicine" to "Medicina dada",
        "Groomed" to "Cepillado hecho", "Cleaned litter" to "Arenero limpio", "Vaccinated" to "Vacunado", "Dewormed" to "Desparasitado",
        "Gave tick & flea care" to "Antiparasitario dado", "Saw the vet" to "Fue al veterinario",
        "Dog" to "Perro", "Cat" to "Gato", "Other" to "Otro", "Your pet" to "Tu mascota",
        // Moods and bubbles
        "{0} is sleeping" to "{0} está durmiendo", "{0} is being looked after" to "Alguien cuida de {0}", "{0} misses you" to "{0} te extraña",
        "{0} is hungry" to "{0} tiene hambre", "{0} is thirsty" to "{0} tiene sed", "{0} wants a walk" to "{0} quiere pasear",
        "{0} wants to play" to "{0} quiere jugar", "The litter needs cleaning" to "Hay que limpiar el arenero", "{0} needs grooming" to "{0} necesita cepillado",
        "Time for {0}'s {1}" to "Hora de {1} de {0}", "{0}'s {1} is due" to "Toca {1} de {0}", "{0} is happy!" to "¡{0} está feliz!",
        "{0} is doing fine" to "{0} está bien", "{0} still needs their {1}." to "A {0} todavía le falta {1}.",
        "{0}'s water bowl needs a refill." to "El bebedero de {0} necesita agua.", "{0} wants to play!" to "¡{0} quiere jugar!",
        "Time to groom {0}." to "Hora de cepillar a {0}.", "Time to clean {0}'s litter." to "Hora de limpiar el arenero de {0}.",
        "Reminder for {0}: {1}." to "Recordatorio para {0}: {1}.", "{0} is getting hungry. Tap Done after feeding." to "{0} empieza a tener hambre. Toca Hecho después de darle de comer.",
        "{0} is ready for a walk!" to "¡{0} está listo para pasear!", "Time for {0}'s {1}." to "Hora de {1} de {0}.",
        "{0}'s {1} is due in 3 days. A good time to book the vet." to "{1} de {0} toca en 3 días. Buen momento para pedir cita.",
        "{0}'s {1} is due today." to "{1} de {0} toca hoy.", "{0}'s {1} is still due. Tap Done in PawPixel once it's given." to "{1} de {0} sigue pendiente. Toca Hecho en PawPixel cuando se la den.",
        "Health care · {0}" to "Salud · {0}", "Care time · {0}" to "Hora de cuidados · {0}", "{0}. Tap Done when it's all done." to "{0}. Toca Hecho cuando esté todo listo.",
        " and " to " y ", "{0} is being looked after while you're away." to "Cuidan de {0} mientras no estás.",
        "Tap Done when you care for {0}, and it shows here." to "Toca Hecho cuando cuides de {0}, y aparecerá aquí.",
        "You cared for {0} every day this week!" to "¡Cuidaste de {0} todos los días esta semana!", "You cared for {0} on {1} of the last 7 days." to "Cuidaste de {0} {1} de los últimos 7 días.",
        "No date yet" to "Sin fecha aún", "Due today" to "Toca hoy", "Overdue by 1 day" to "Atrasado 1 día", "Overdue by {0} days" to "Atrasado {0} días",
        "Due in 1 day" to "Toca en 1 día", "Due in {0} days" to "Toca en {0} días", "Due in 1 month" to "Toca en 1 mes", "Due in {0} months" to "Toca en {0} meses",
        "A whole year of care" to "Un año entero de cuidados", "Two years of care" to "Dos años de cuidados", "{0} days of care" to "{0} días de cuidados",
        "Not born yet" to "Aún no nació", "{0} days old" to "{0} días", "{0} weeks old" to "{0} semanas", "{0} months old" to "{0} meses", "{0} years old" to "{0} años",
        "{0} days together" to "{0} días juntos", "Forever in your heart" to "Siempre en tu corazón",
        // Buttons and screens
        "‹ Back" to "‹ Atrás", "Back" to "Atrás", "OK" to "OK", "Cancel" to "Cancelar", "Save" to "Guardar", "Edit" to "Editar", "Delete" to "Eliminar", "Done" to "Hecho",
        "Undo" to "Deshacer", "Close" to "Cerrar", "Remove" to "Quitar", "Show" to "Mostrar", "Hide" to "Ocultar", "Got it" to "Entendido", "Not now" to "Ahora no",
        "Name" to "Nombre", "Nice!" to "¡Genial!", "Share" to "Compartir", "Copy" to "Copiar", "Settings" to "Ajustes", "More" to "Más",
        "Care" to "Cuidados", "Health" to "Salud", "Wardrobe" to "Armario", "Album" to "Álbum", "Weight" to "Peso", "Pet map" to "Mapa de mascotas",
        "Your pets" to "Tus mascotas", "Pals" to "Amigos", "Lost and Found" to "Perdidos y encontrados", "Vet visit" to "Visita al veterinario",
        "Turn your pet into pixel art" to "Convierte a tu mascota en pixel art", "Choose a photo" to "Elegir una foto", "+ Add another pet" to "+ Añadir otra mascota",
        "Put your pet on your home screen" to "Pon a tu mascota en la pantalla de inicio", "Add widget" to "Añadir widget",
        "Happiness {0} of 5" to "Felicidad {0} de 5", "Tap {0} to give pets" to "Toca a {0} para acariciarlo",
        "+ Add care task" to "+ Añadir tarea", "Share animation" to "Compartir animación", "Before/after" to "Antes/después",
        "Waiting since {0}" to "Esperando desde {0}", "Done · next {0}" to "Hecho · próximo {0}", "All done today ✓" to "Todo hecho hoy ✓", "Next {0}" to "Próximo {0}",
        "This week" to "Esta semana", "Today" to "Hoy", "Yesterday" to "Ayer", "Daily" to "Diario", "Weekly" to "Semanal", "Monthly" to "Mensual", "Yearly" to "Anual",
        "Reminders" to "Recordatorios", "Get a notification when it's time." to "Recibe un aviso cuando sea la hora.", "Outfits" to "Trajes",
        "Pet's name" to "Nombre de la mascota", "Is that your pet? Tap to give pets." to "¿Es tu mascota? Toca para acariciarla.",
        "Start a walk" to "Empezar un paseo", "End walk" to "Terminar paseo", "Walks this week" to "Paseos esta semana",
        "Lost?" to "¿Perdido?", "Alert pet owners nearby" to "Avisar a dueños cercanos", "Safe home!" to "¡En casa!", "I saw them" to "Lo vi",
        "Happy birthday, {0}!" to "¡Feliz cumpleaños, {0}!", "Phone's language" to "Idioma del teléfono",
    )
}
