$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOut=Join-Path $taskRoot 'build/test-speech-lifecycle'
New-Item -ItemType Directory -Force $taskOut | Out-Null
$taskSources=@("$PSScriptRoot/tts-stubs/android/content/Context.java","$PSScriptRoot/tts-stubs/android/speech/tts/TextToSpeech.java","$taskRoot/java/com/afs/guard/Speech.java","$PSScriptRoot/tts-stubs/com/afs/guard/SpeechLifecycleTest.java")
& javac --release 8 -encoding UTF-8 -d $taskOut @taskSources
if($LASTEXITCODE -ne 0){throw 'Speech lifecycle compilation failed'}
& java '-Dfile.encoding=UTF-8' -cp $taskOut com.afs.guard.SpeechLifecycleTest
if($LASTEXITCODE -ne 0){throw 'Speech lifecycle regression failed'}
