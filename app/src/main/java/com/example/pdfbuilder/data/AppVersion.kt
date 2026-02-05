package com.example.pdfbuilder.data

data class AppVersion(
    val version: String,      // e.g. "1.0.15"
    val changelog: String,    // Update content
    val downloadUrl: String,  // Direct download URL
    val date: String          // e.g. "2026-02-05"
)
