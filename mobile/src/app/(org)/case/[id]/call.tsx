import { useEffect, useRef, useState } from 'react';
import { View, AppState, Linking } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import {
  useAudioRecorder, RecordingPresets, requestRecordingPermissionsAsync, setAudioModeAsync,
} from 'expo-audio';
import { Phone, Mic, MicOff, Pause, Play } from 'lucide-react-native';
import CallRecording from 'call-recording';
import { useTheme } from '@/theme/useTheme';
import {
  Screen, Text, Button, Card, TextField, SelectField, LoadingView,
} from '@/components/ui';
import { callLogApi } from '@/api/callLogApi';
import { extractApiError } from '@/utils/extractApiError';
import type { CallOutcome } from '@/types/domain';

const OUTCOME_OPTIONS: { label: string; value: CallOutcome }[] = [
  { label: 'Answered', value: 'ANSWERED' },
  { label: 'No answer', value: 'NO_ANSWER' },
  { label: 'Busy', value: 'BUSY' },
  { label: 'Wrong number', value: 'WRONG_NUMBER' },
  { label: 'Callback requested', value: 'CALLBACK_REQUESTED' },
  { label: 'Refused', value: 'REFUSED' },
  { label: 'Switched off', value: 'SWITCHED_OFF' },
];

type Phase = 'starting' | 'calling' | 'review' | 'submitting' | 'error';

export default function CallScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { colors, spacing } = useTheme();
  const recorder = useAudioRecorder(RecordingPresets.HIGH_QUALITY);

  const [phase, setPhase] = useState<Phase>('starting');
  const [error, setError] = useState<string | null>(null);
  const [micActive, setMicActive] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [outcome, setOutcome] = useState<CallOutcome | undefined>();
  const [notes, setNotes] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const callLogIdRef = useRef<string | null>(null);
  const startedAtRef = useRef<number | null>(null);
  const durationRef = useRef<number | undefined>(undefined);
  const recordingActiveRef = useRef(false);
  const pausedRef = useRef(false);
  const hasLeftRef = useRef(false);

  const beginRecording = async () => {
    try {
      const perm = await requestRecordingPermissionsAsync().catch(() => null);
      if (!perm?.granted) return;
      await setAudioModeAsync({ allowsRecording: true, playsInSilentMode: true }).catch(() => {});
      await CallRecording.startForegroundRecording().catch(() => {});
      await recorder.prepareToRecordAsync();
      recorder.record();
      recordingActiveRef.current = true;
      setMicActive(true);
    } catch {
      // Best-effort — a failed recording start must not block placing the call.
    }
  };

  const stopRecording = async () => {
    if (recordingActiveRef.current) {
      try { await recorder.stop(); } catch { /* already stopped */ }
      recordingActiveRef.current = false;
      pausedRef.current = false;
      setMicActive(false);
      setIsPaused(false);
    }
    await CallRecording.stopForegroundRecording().catch(() => {});
  };

  const pauseRecording = () => {
    if (!recordingActiveRef.current || pausedRef.current) return;
    try {
      recorder.pause();
      pausedRef.current = true;
      setIsPaused(true);
    } catch { /* ignore */ }
  };

  const resumeRecording = () => {
    if (!recordingActiveRef.current || !pausedRef.current) return;
    try {
      recorder.record();
      pausedRef.current = false;
      setIsPaused(false);
    } catch { /* ignore */ }
  };

  useEffect(() => {
    if (!id) return;
    let cancelled = false;

    (async () => {
      try {
        const { callLogId, phoneNumber } = await callLogApi.start(id);
        if (cancelled) return;
        callLogIdRef.current = callLogId;
        await beginRecording();
        if (cancelled) return;
        startedAtRef.current = Date.now();
        setPhase('calling');
        Linking.openURL(`tel:${phoneNumber}`).catch(() => {});
      } catch (e) {
        if (!cancelled) {
          setError(extractApiError(e, 'Could not start the call. The borrower may have no phone number on file.'));
          setPhase('error');
        }
      }
    })();

    return () => { cancelled = true; };
  }, [id]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (phase !== 'calling') return;
      if (state !== 'active') { hasLeftRef.current = true; return; }
      if (!hasLeftRef.current) return;
      hasLeftRef.current = false;

      durationRef.current = startedAtRef.current
        ? Math.round((Date.now() - startedAtRef.current) / 1000)
        : undefined;
      stopRecording().finally(() => setPhase('review'));
    });
    return () => sub.remove();
  }, [phase]);

  useEffect(() => () => {
    if (recordingActiveRef.current) {
      recorder.stop().catch(() => {});
      CallRecording.stopForegroundRecording().catch(() => {});
    }
  }, []);

  // Mirrors the Pause/Resume/Stop actions on the persistent recording notification, so control
  // works the same whether the agent is in-app or has backgrounded it to use the phone dialer.
  useEffect(() => {
    const subs = [
      CallRecording.addPauseRequestedListener(pauseRecording),
      CallRecording.addResumeRequestedListener(resumeRecording),
      CallRecording.addStopRequestedListener(() => { stopRecording(); }),
    ];
    return () => subs.forEach((sub) => sub.remove());
  }, []);

  const onSubmitOutcome = async () => {
    if (!outcome) { setFormError('Select what happened on the call.'); return; }
    if (!callLogIdRef.current) return;
    setFormError(null);
    setPhase('submitting');

    if (outcome === 'ANSWERED' && recorder.uri) {
      await callLogApi
        .uploadRecording(callLogIdRef.current, { uri: recorder.uri, name: `call-${callLogIdRef.current}.m4a`, type: 'audio/m4a' })
        .catch(() => {});
    }

    try {
      await callLogApi.complete(callLogIdRef.current, {
        outcome, notes: notes || undefined, durationSeconds: durationRef.current,
      });
      router.replace({ pathname: '/case/[id]', params: { id } });
    } catch (e) {
      setFormError(extractApiError(e, 'Could not save the call outcome. Try again.'));
      setPhase('review');
    }
  };

  if (phase === 'starting') return <LoadingView label="Starting call…" />;

  if (phase === 'error') {
    return (
      <Screen>
        <View style={{ gap: spacing.s4, alignItems: 'center', marginTop: spacing.s8 }}>
          <Text variant="title" style={{ textAlign: 'center' }}>Couldn&apos;t start the call</Text>
          <Text variant="body" color="secondary" style={{ textAlign: 'center' }}>{error}</Text>
          <Button label="Go back" variant="secondary" onPress={() => router.back()} />
        </View>
      </Screen>
    );
  }

  if (phase === 'calling') {
    return (
      <Screen scroll={false}>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing.s4 }}>
          <View style={{
            width: 88, height: 88, borderRadius: 44, backgroundColor: colors.successSubtle,
            alignItems: 'center', justifyContent: 'center',
          }}
          >
            <Phone size={40} color={colors.accent} />
          </View>
          <Text variant="title">Call in progress</Text>
          <Text variant="body" color="secondary" style={{ textAlign: 'center', maxWidth: 280 }}>
            Return to this app once you hang up to record the call outcome.
          </Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
            {micActive ? <Mic size={16} color={colors.ink3} /> : <MicOff size={16} color={colors.ink3} />}
            <Text variant="caption" color="secondary">
              {!micActive && 'Recording unavailable — proceeding without it'}
              {micActive && isPaused && 'Recording paused'}
              {micActive && !isPaused && 'Recording for audit'}
            </Text>
          </View>
          {micActive ? (
            <Button
              label={isPaused ? 'Resume recording' : 'Pause recording'}
              variant="secondary"
              fullWidth={false}
              icon={isPaused
                ? <Play size={18} color={colors.ink1} />
                : <Pause size={18} color={colors.ink1} />}
              onPress={isPaused ? resumeRecording : pauseRecording}
            />
          ) : null}
        </View>
      </Screen>
    );
  }

  return (
    <Screen>
      <View style={{ gap: spacing.s4 }}>
        <Text variant="title" style={{ fontSize: 20 }}>How did the call go?</Text>
        <Card style={{ gap: spacing.s4 }}>
          <SelectField
            label="Outcome"
            required
            placeholder="Select outcome"
            value={outcome}
            options={OUTCOME_OPTIONS}
            onChange={setOutcome}
          />
          <TextField label="Notes" value={notes} onChangeText={setNotes} multiline placeholder="Any details worth recording" />
        </Card>
        {formError ? <Text variant="caption" color="error">{formError}</Text> : null}
        <Button label="Save call outcome" onPress={onSubmitOutcome} loading={phase === 'submitting'} />
      </View>
    </Screen>
  );
}
