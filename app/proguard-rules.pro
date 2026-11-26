# SPDX-FileCopyrightText: 2026 David Ventura
# SPDX-License-Identifier: GPL-3.0-only

# Preserve file names and line numbers for readable stack traces.
# Retrace obfuscated traces with app/build/outputs/mapping/release/mapping.txt.
-keepattributes SourceFile,LineNumberTable

# Replace source file paths with just "SourceFile" to avoid leaking build paths
-renamesourcefileattribute SourceFile

# Full R8 optimization and renaming. Classes that Android instantiates by name
# (manifest components) keep their names through the rules AAPT2 generates.
# Moving the renamed classes into one package shortens names in the dex.
-repackageclasses
