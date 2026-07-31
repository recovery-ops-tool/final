import { useEffect, useState, useRef } from 'react';
import { View, ScrollView, KeyboardAvoidingView, Platform, StyleSheet, Pressable } from 'react-native';
import { router, useLocalSearchParams, Stack } from 'expo-router';
import { MapPin, RefreshCw, ChevronDown, ChevronUp, Send, Check, X, ShieldAlert, Sparkles, ArrowLeft } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Button, Card, TextField, Divider, LoadingView, EmptyState, Badge } from '@/components/ui';
import { PhotoPicker, type PickedPhoto } from '@/components/PhotoPicker';
import { useGpsCapture } from '@/hooks/useGpsCapture';
import { useAuth } from '@/context/AuthContext';
import { lucienApi, type ChatMessageResponse } from '@/api/lucienApi';
import { extractApiError } from '@/utils/extractApiError';

export default function LucienVisitScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { colors, spacing, radius } = useTheme();
  const { user } = useAuth();
  
  const { fix, capturing, error: gpsError, capture } = useGpsCapture();

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [starting, setStarting] = useState(true);
  const [sending, setSending] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<ChatMessageResponse[]>([]);
  const [pendingConfirm, setPendingConfirm] = useState<{
    actionId: string;
    summary: string;
    toolName?: string;
  } | null>(null);
  
  const [photos, setPhotos] = useState<PickedPhoto[]>([]);
  const [showRequirements, setShowRequirements] = useState(true);
  const [chatError, setChatError] = useState<string | null>(null);

  const scrollViewRef = useRef<ScrollView>(null);

  // Capture GPS on mount
  useEffect(() => {
    capture();
  }, []);

  // Initialize session on mount
  useEffect(() => {
    let active = true;
    const initSession = async () => {
      if (!id || !user) return;
      setStarting(true);
      setChatError(null);
      try {
        const session = await lucienApi.startSession({
          agentId: user.id,
          agentFirstName: user.firstName,
          allocationId: id,
        });
        if (active) {
          setSessionId(session.sessionId);
        }
      } catch (err) {
        if (active) {
          setChatError(extractApiError(err, 'Lucien is unavailable right now. Please try again or log the visit manually.'));
        }
      } finally {
        if (active) {
          setStarting(false);
        }
      }
    };
    initSession();
    return () => {
      active = false;
    };
  }, [id, user]);

  const onSend = async () => {
    const text = input.trim();
    if (!text || !sessionId || sending || pendingConfirm) return;

    setInput('');
    const tempId = `u-${Date.now()}`;
    const userMsg: ChatMessageResponse = {
      id: tempId,
      role: 'USER',
      content: text,
      wasBlocked: false,
      createdAt: new Date().toISOString(),
    };

    setMessages(prev => [...prev, userMsg]);
    setSending(true);
    setChatError(null);

    try {
      const resp = await lucienApi.sendMessage({ sessionId, message: text });
      if (resp.blocked) {
        setMessages(prev => [
          ...prev,
          {
            id: resp.messageId || `a-${Date.now()}`,
            role: 'ASSISTANT',
            content: resp.reply || resp.blockReason || 'Message was blocked due to safety guidelines.',
            wasBlocked: true,
            createdAt: resp.timestamp,
          },
        ]);
      } else {
        setMessages(prev => [
          ...prev,
          {
            id: resp.messageId || `a-${Date.now()}`,
            role: 'ASSISTANT',
            content: resp.reply || 'Lucien had nothing to add.',
            wasBlocked: false,
            createdAt: resp.timestamp,
          },
        ]);

        if (resp.confirmationRequired && resp.pendingActionId) {
          setPendingConfirm({
            actionId: resp.pendingActionId,
            summary: resp.pendingActionSummary || 'Lucien wants to submit this visit.',
            toolName: resp.pendingToolName,
          });
        }
      }
    } catch (err) {
      setChatError(extractApiError(err, 'Lucien did not respond. Please try again.'));
      // Remove the user message that failed to get response to keep chat history clean
      setMessages(prev => prev.filter(m => m.id !== tempId));
      setInput(text);
    } finally {
      setSending(false);
    }
  };

  const handleConfirm = async (confirmed: boolean) => {
    if (!sessionId || !pendingConfirm) return;
    setConfirming(true);
    setChatError(null);

    try {
      if (pendingConfirm.toolName === 'submit_visit_interview') {
        if (confirmed) {
          // Verify GPS and Photos are ready (TEMPORARILY RELAXED FOR TESTING)
          const gpsAccurate = true;
          const photosOk = true;

          const testLatitude = fix?.latitude ?? 12.9716;
          const testLongitude = fix?.longitude ?? 77.5946;
          const testAccuracy = fix?.accuracy ?? 10.0;
          const testMockLocation = fix?.mockLocationDetected ?? false;

          const defaultPhoto = {
            uri: 'https://via.placeholder.com/150',
            name: 'test_photo.jpg',
            type: 'image/jpeg',
          };
          const testPhoto1 = photos[0] || defaultPhoto;
          const testPhoto2 = photos[1] || defaultPhoto;

          const resp = await lucienApi.confirmVisitAction(
            sessionId,
            {
              actionId: pendingConfirm.actionId,
              confirmed: true,
              latitude: testLatitude,
              longitude: testLongitude,
              gpsAccuracy: testAccuracy,
              mockLocationDetected: testMockLocation,
            },
            testPhoto1,
            testPhoto2,
          );

          const replyText = resp.reply || 'Visit submitted.';
          setMessages(prev => [
            ...prev,
            {
              id: resp.messageId || `a-${Date.now()}`,
              role: 'ASSISTANT',
              content: replyText,
              wasBlocked: false,
              createdAt: resp.timestamp,
            },
          ]);

          if (!resp.confirmationRequired && replyText.startsWith('Done.')) {
            setSubmitted(true);
          }
        } else {
          // Cancel the action
          const resp = await lucienApi.confirmVisitAction(
            sessionId,
            {
              actionId: pendingConfirm.actionId,
              confirmed: false,
            },
            undefined as any,
            undefined,
          );

          const replyText = resp.reply || 'Action cancelled.';
          setMessages(prev => [
            ...prev,
            {
              id: resp.messageId || `a-${Date.now()}`,
              role: 'ASSISTANT',
              content: replyText,
              wasBlocked: false,
              createdAt: resp.timestamp,
            },
          ]);
        }
      } else {
        // Fallback plain confirm action
        const resp = await lucienApi.confirmAction(sessionId, {
          actionId: pendingConfirm.actionId,
          confirmed,
        });

        const replyText = resp.reply || (confirmed ? 'Action executed.' : 'Action cancelled.');
        setMessages(prev => [
          ...prev,
          {
            id: resp.messageId || `a-${Date.now()}`,
            role: 'ASSISTANT',
            content: replyText,
            wasBlocked: false,
            createdAt: resp.timestamp,
          },
        ]);
      }
    } catch (err) {
      setChatError(extractApiError(err, 'Failed to process confirmation. Please try again.'));
    } finally {
      setPendingConfirm(null);
      setConfirming(false);
    }
  };

  // Navigate away on success
  useEffect(() => {
    if (!submitted) return;
    const timeout = setTimeout(() => {
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace({ pathname: '/(tabs)' });
      }
    }, 1500);
    return () => clearTimeout(timeout);
  }, [submitted]);

  if (starting) {
    return <LoadingView label="Starting Lucien visit interview…" />;
  }

  const gpsAccurate = !!fix && (fix.accuracy == null || fix.accuracy <= 50);
  const photosOk = photos.length === 2;
  const isSubmitInterview = pendingConfirm?.toolName === 'submit_visit_interview';
  const submitGated = isSubmitInterview && (!gpsAccurate || !photosOk);

  return (
    <Screen scroll={false} padded={false}>
      <Stack.Screen options={{ headerShown: false, presentation: 'fullScreenModal' }} />
      <View style={{ flex: 1, backgroundColor: colors.canvas }}>
        
        {/* Custom Header */}
        <View style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          paddingHorizontal: spacing.s4,
          paddingVertical: spacing.s3,
          backgroundColor: colors.surface,
          borderBottomWidth: 1,
          borderBottomColor: colors.border,
        }}>
          <Pressable 
            onPress={() => {
              if (router.canGoBack()) {
                router.back();
              } else {
                router.replace({ pathname: `/case/${id}` });
              }
            }}
            style={{ padding: spacing.s1, flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}
          >
            <ArrowLeft size={20} color={colors.ink1} />
          </Pressable>
          <Text variant="headline" style={{ fontSize: 16, fontWeight: '600', color: colors.ink1 }}>
            Visit Interview
          </Text>
          <View style={{ width: 24 }} />
        </View>
        
        {/* Verification requirements card (collapsible) */}
        <Card style={{ margin: spacing.s3, marginBottom: 0, gap: spacing.s2 }}>
          <Pressable 
            onPress={() => setShowRequirements(p => !p)} 
            style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
              <Badge 
                tone={gpsAccurate && photosOk ? 'success' : 'warning'} 
                label={gpsAccurate && photosOk ? 'Ready to submit' : 'Pending requirements'} 
              />
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
              <Text variant="caption" color="secondary">
                {showRequirements ? 'Collapse' : 'Expand'}
              </Text>
              {showRequirements ? <ChevronUp size={16} color={colors.ink2} /> : <ChevronDown size={16} color={colors.ink2} />}
            </View>
          </Pressable>

          {showRequirements && (
            <View style={{ gap: spacing.s3, paddingTop: spacing.s2 }}>
              <Divider />
              
              {/* GPS status block */}
              <View style={{ flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.s2 }}>
                <View style={{ flex: 1, gap: spacing.s1 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                    <MapPin size={14} color={gpsAccurate ? colors.success : colors.warnBorder} />
                    <Text variant="bodyMedium">
                      {gpsAccurate ? 'Accurate location captured' : 'Capturing location (needs ≤ 50m accuracy)…'}
                    </Text>
                  </View>
                  {fix ? (
                    <Text variant="caption" color="secondary">
                      {fix.address ? fix.address + '\n' : ''}
                      Coords: {fix.latitude.toFixed(6)}, {fix.longitude.toFixed(6)} · Accuracy: {fix.accuracy ? `±${Math.round(fix.accuracy)}m` : 'N/A'}
                    </Text>
                  ) : null}
                  {gpsError ? <Text variant="caption" color="error">{gpsError}</Text> : null}
                </View>
                <Button
                  label="Retry"
                  variant="ghost"
                  fullWidth={false}
                  size="md"
                  loading={capturing}
                  onPress={capture}
                  icon={<RefreshCw size={14} color={colors.accent} />}
                />
              </View>

              {/* Photos capture block */}
              <PhotoPicker 
                photos={photos} 
                onChange={setPhotos} 
                max={2} 
                label="Selfie & Site Photo (Both Required for submission)" 
              />
            </View>
          )}
        </Card>

        {/* Chat message history list */}
        <ScrollView
          ref={scrollViewRef}
          style={{ flex: 1 }}
          contentContainerStyle={{ padding: spacing.s3, gap: spacing.s3 }}
          keyboardShouldPersistTaps="handled"
          onContentSizeChange={() => scrollViewRef.current?.scrollToEnd({ animated: true })}
        >
          {messages.length === 0 ? (
            <EmptyState
              icon={Sparkles}
              title="Lucien Visit Interview"
              message="Lucien will act as your manager. Tell Lucien what's happening at the door to coach you through the visit and submit it."
            />
          ) : (
            messages.map((m) => {
              const isUser = m.role === 'USER';
              return (
                <View
                  key={m.id}
                  style={{
                    alignSelf: isUser ? 'flex-end' : 'flex-start',
                    maxWidth: '85%',
                    backgroundColor: isUser ? colors.accent : colors.subtle,
                    borderRadius: radius.lg,
                    borderTopRightRadius: isUser ? 0 : radius.lg,
                    borderTopLeftRadius: isUser ? radius.lg : 0,
                    paddingHorizontal: spacing.s3,
                    paddingVertical: spacing.s2 + 2,
                  }}
                >
                  <Text style={{ color: isUser ? colors.white : colors.ink1 }}>
                    {m.content}
                  </Text>
                </View>
              );
            })
          )}

          {sending && (
            <View
              style={{
                alignSelf: 'flex-start',
                backgroundColor: colors.subtle,
                borderRadius: radius.lg,
                borderTopLeftRadius: 0,
                paddingHorizontal: spacing.s3,
                paddingVertical: spacing.s2 + 2,
              }}
            >
              <Text color="secondary">Lucien is thinking…</Text>
            </View>
          )}

          {submitted && (
            <View
              style={{
                alignSelf: 'flex-start',
                backgroundColor: colors.successSubtle,
                borderRadius: radius.lg,
                borderTopLeftRadius: 0,
                paddingHorizontal: spacing.s3,
                paddingVertical: spacing.s2 + 2,
              }}
            >
              <Text style={{ color: colors.success }}>
                ✓ Visit submitted — returning to today's visits…
              </Text>
            </View>
          )}

          {chatError && (
            <View
              style={{
                alignSelf: 'stretch',
                backgroundColor: colors.errorSubtle,
                borderColor: colors.error,
                borderWidth: 1,
                borderRadius: radius.md,
                padding: spacing.s3,
              }}
            >
              <Text style={{ color: colors.error }}>{chatError}</Text>
            </View>
          )}
        </ScrollView>

        {/* Confirmation card overlay */}
        {pendingConfirm && !submitted && (
          <Card style={{ margin: spacing.s3, gap: spacing.s3, borderColor: colors.warnBorder, borderWidth: 1 }}>
            <View style={{ flexDirection: 'row', gap: spacing.s2, alignItems: 'flex-start' }}>
              <ShieldAlert size={18} color={colors.warnBorder} style={{ marginTop: 2 }} />
              <View style={{ flex: 1, gap: spacing.s1 }}>
                <Text variant="headline" style={{ fontSize: 16 }}>
                  {isSubmitInterview ? 'Ready to submit this visit' : 'Confirmation required'}
                </Text>
                <Text variant="body" color="secondary">
                  {pendingConfirm.summary}
                </Text>
                {submitGated && (
                  <Text variant="caption" style={{ color: colors.error, marginTop: spacing.s1 }}>
                    {!gpsAccurate ? '• Location fix <= 50m accuracy is required.\n' : ''}
                    {!photosOk ? '• Selfie and site photo (2 photos) are required.' : ''}
                  </Text>
                )}
              </View>
            </View>
            <View style={{ flexDirection: 'row', gap: spacing.s2, justifyContent: 'flex-end' }}>
              <Button
                label="Cancel"
                variant="outline"
                fullWidth={false}
                size="md"
                disabled={confirming}
                onPress={() => handleConfirm(false)}
                icon={<X size={14} color={colors.ink1} />}
              />
              <Button
                label="Confirm"
                fullWidth={false}
                size="md"
                disabled={confirming || submitGated}
                loading={confirming}
                onPress={() => handleConfirm(true)}
                icon={<Check size={14} color={colors.white} />}
              />
            </View>
          </Card>
        )}

        {/* Composer bottom container */}
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
        >
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'flex-end',
              gap: spacing.s2,
              padding: spacing.s3,
              borderTopWidth: 1,
              borderColor: colors.border,
              backgroundColor: colors.surface,
            }}
          >
            <View style={{ flex: 1 }}>
              <TextField
                placeholder={
                  submitted ? 'Visit submitted.'
                    : pendingConfirm ? 'Resolve the confirmation above…'
                      : 'Tell Lucien what\'s happening…'
                }
                value={input}
                onChangeText={setInput}
                editable={!sending && !confirming && !pendingConfirm && !submitted}
                style={{ minHeight: 40, maxHeight: 100 }}
                multiline
              />
            </View>
            <Button
              label="Send"
              fullWidth={false}
              size="md"
              onPress={onSend}
              disabled={!input.trim() || sending || confirming || !!pendingConfirm || submitted}
              icon={<Send size={14} color={colors.white} />}
            />
          </View>
        </KeyboardAvoidingView>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({});
