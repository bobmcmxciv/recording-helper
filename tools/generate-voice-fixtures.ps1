# Generates synthetic voices only. Does not capture the microphone or play through speakers.
param([string]$OutputDirectory = (Join-Path $PSScriptRoot '../_build/voice-fixtures'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$enroll = 'This is a sample for the local recording assistant. I am speaking naturally in a quiet room. The application keeps the recording on the phone and compares the acoustic patterns without sending them to a server. Today we are planning a meeting, reviewing important notes, and preparing the next report. This is only a synthetic voice used for a software test.'
$check = 'Tomorrow I will review the project schedule and check each item on my task list. This sentence is different from the enrollment sample. The purpose of this recording is to check whether a speaker comparison remains consistent when the words change. The results are experimental and should not be used as proof of a real identity.'
try {
    foreach ($case in @(@('speaker-a-enroll.wav', 'Microsoft David Desktop', $enroll), @('speaker-a-check.wav', 'Microsoft David Desktop', $check), @('speaker-b-enroll.wav', 'Microsoft Zira Desktop', $enroll), @('speaker-b-check.wav', 'Microsoft Zira Desktop', $check))) {
        $synth.SelectVoice($case[1])
        $synth.SetOutputToWaveFile((Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $case[0]), $format)
        $synth.Speak($case[2])
        $synth.SetOutputToNull()
    }
} finally { $synth.Dispose() }
Get-ChildItem -LiteralPath $OutputDirectory -Filter '*.wav' | Select-Object Name, Length
