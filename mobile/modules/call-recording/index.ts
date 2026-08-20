import { requireOptionalNativeModule, EventEmitter, type EventSubscription } from 'expo-modules-core';

interface CallRecordingNativeModule {
  startForegroundRecording(): Promise<void>;
  stopForegroundRecording(): Promise<void>;
}

type CallRecordingEvents = {
  onPauseRequested(): void;
  onResumeRequested(): void;
  onStopRequested(): void;
};

// Android-only native module. Absent on iOS/web and in Expo Go (needs a dev client build) —
// every method below degrades to a safe no-op in that case rather than throwing, since a
// missing recording keep-alive must never block placing the call.
const CallRecordingNative = requireOptionalNativeModule<CallRecordingNativeModule>('CallRecording');
const emitter = CallRecordingNative
  ? new EventEmitter<CallRecordingEvents>(CallRecordingNative as any)
  : null;

const noopSubscription: EventSubscription = { remove: () => {} };

const CallRecording = {
  startForegroundRecording: () => CallRecordingNative?.startForegroundRecording() ?? Promise.resolve(),
  stopForegroundRecording: () => CallRecordingNative?.stopForegroundRecording() ?? Promise.resolve(),
  /** Fired when the agent taps "Pause" on the persistent recording notification. */
  addPauseRequestedListener(listener: () => void): EventSubscription {
    return emitter?.addListener('onPauseRequested', listener) ?? noopSubscription;
  },
  /** Fired when the agent taps "Resume" on the persistent recording notification. */
  addResumeRequestedListener(listener: () => void): EventSubscription {
    return emitter?.addListener('onResumeRequested', listener) ?? noopSubscription;
  },
  /** Fired when the agent taps "Stop" on the persistent recording notification. */
  addStopRequestedListener(listener: () => void): EventSubscription {
    return emitter?.addListener('onStopRequested', listener) ?? noopSubscription;
  },
};

export default CallRecording;
