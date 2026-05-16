package com.nxiwnetwork.client

import java.io.File

enum class CoreBackend(
    val id: String,
    val label: String,
    val description: String,
    val binaryName: String
) {
    Go(
        id = "go",
        label = "Go",
        description = "Стабильное ядро клиента. Полный текущий функционал.",
        binaryName = "libcore_go.so"
    ),
    Rust(
        id = "rust",
        label = "Rust",
        description = "Экспериментальное ядро клиента. Тот же процессный контракт, флаги и логи, но реализация data path на Rust.",
        binaryName = "libcore_rs.so"
    );

    fun binaryFile(nativeLibraryDir: String): File = File(nativeLibraryDir, binaryName)

    companion object {
        val selectable: List<CoreBackend> = listOf(Go, Rust)

        fun fromId(id: String?): CoreBackend {
            return selectable.firstOrNull { it.id == id?.lowercase() } ?: Go
        }

        fun normalize(id: String?): String = fromId(id).id
    }
}

data class CoreBackendResolution(
    val requested: CoreBackend,
    val active: CoreBackend,
    val binaryFile: File,
    val fellBackToGo: Boolean
)

fun resolveCoreBackend(nativeLibraryDir: String, requested: CoreBackend): CoreBackendResolution {
    val requestedFile = requested.binaryFile(nativeLibraryDir)
    if (requestedFile.exists()) {
        return CoreBackendResolution(
            requested = requested,
            active = requested,
            binaryFile = requestedFile,
            fellBackToGo = false
        )
    }

    val goFile = CoreBackend.Go.binaryFile(nativeLibraryDir)
    return CoreBackendResolution(
        requested = requested,
        active = CoreBackend.Go,
        binaryFile = goFile,
        fellBackToGo = requested != CoreBackend.Go
    )
}
