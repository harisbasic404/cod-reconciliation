[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("cod-bcrypt-" + [System.IO.Path]::GetRandomFileName())
$sourceFile = Join-Path $tempRoot "BcryptHashGenerator.java"
$classpathFile = Join-Path $tempRoot "classpath.txt"
$maven = Join-Path $PSScriptRoot "mvnw.cmd"

$javaSource = @'
import java.io.Console;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class BcryptHashGenerator {
    private BcryptHashGenerator() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Run this script from an interactive PowerShell console.");
        }

        char[] password = console.readPassword("Administrator password (input hidden): ");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password must not be empty.");
        }

        try {
            String hash = new BCryptPasswordEncoder().encode(new String(password));
            System.out.println("{bcrypt}" + hash);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
'@

try {
    if (-not (Test-Path -LiteralPath $maven)) {
        throw "Maven wrapper not found at $maven."
    }

    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($sourceFile, $javaSource, $utf8WithoutBom)

    & $maven -q -DincludeScope=compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile" *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) {
        throw "Could not resolve the existing Maven dependencies."
    }

    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    & javac -encoding UTF-8 -cp $classpath -d $tempRoot $sourceFile
    if ($LASTEXITCODE -ne 0) {
        throw "Could not compile the temporary BCrypt generator."
    }

    & java -cp "$tempRoot;$classpath" BcryptHashGenerator
    if ($LASTEXITCODE -ne 0) {
        throw "BCrypt generation failed."
    }
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
