import { useState } from 'react';
import { View, Pressable, ScrollView, Alert, Image } from 'react-native';
import { router } from 'expo-router';
import { ChevronLeft, AlertCircle, CheckCircle } from 'lucide-react-native';
import { authApi } from '@/api/authApi';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Button, TextField, Card, Divider } from '@/components/ui';
import { extractApiError } from '@/utils/extractApiError';

type Step = 'email' | 'otp' | 'password' | 'success';

export default function ForgotPasswordScreen() {
  const { colors, spacing } = useTheme();

  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState('');
  const [otp, setOtp] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmitEmail = async () => {
    setError(null);
    if (!email.trim()) {
      setError('Please enter your email address.');
      return;
    }

    setSubmitting(true);
    try {
      await authApi.forgotPassword({ email: email.trim() });
      setStep('otp');
    } catch (err) {
      setError(extractApiError(err, 'No account found with this email address.'));
    } finally {
      setSubmitting(false);
    }
  };

  const onVerifyOtp = async () => {
    setError(null);
    if (otp.trim().length < 6) {
      setError('Please enter the complete 6-digit verification code.');
      return;
    }

    setSubmitting(true);
    try {
      await authApi.verifyResetOtp({ email: email.trim(), otp: otp.trim() });
      setStep('password');
    } catch (err) {
      setError(extractApiError(err, 'Invalid or expired code. Please check and try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  const onResendOtp = async () => {
    setError(null);
    setSuccessMsg(null);
    try {
      await authApi.forgotPassword({ email: email.trim() });
      setSuccessMsg('A new reset code has been sent to your email.');
    } catch (err) {
      setError(extractApiError(err, 'Could not resend code. Please try again.'));
    }
  };

  const onSubmitPassword = async () => {
    setError(null);
    if (newPassword.length < 8) {
      setError('Password must be at least 8 characters long.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    setSubmitting(true);
    try {
      await authApi.resetPassword({
        email: email.trim(),
        otp: otp.trim(),
        newPassword,
      });
      setStep('success');
    } catch (err) {
      setError(extractApiError(err, 'Could not reset password. Please try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Screen edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', paddingVertical: spacing.s6 }}>
        <View style={{ gap: spacing.s6 }}>
          
          {/* Header/Branding section */}
          <View style={{ alignItems: 'center', marginBottom: -spacing.s3 }}>
            <Image 
              source={require('../../../assets/images/logo.png')} 
              style={{ width: 120, height: 120, resizeMode: 'contain' }} 
            />
          </View>

          {step === 'email' && (
            <View style={{ gap: spacing.s4 }}>
              <View style={{ gap: spacing.s1, paddingHorizontal: spacing.s2 }}>
                <Text variant="headline" style={{ fontSize: 20, fontWeight: '700', color: colors.ink1 }}>Forgot password?</Text>
                <Text variant="body" color="secondary" style={{ fontSize: 14 }}>
                  Enter the email address linked to your account and we'll send you a reset code.
                </Text>
              </View>

              <Card style={{ gap: spacing.s4, shadowOpacity: 0, elevation: 0 }}>
                <TextField
                  label="Email"
                  required
                  autoCapitalize="none"
                  keyboardType="email-address"
                  autoComplete="email"
                  value={email}
                  onChangeText={setEmail}
                  placeholder="you@company.com"
                />

                {error ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, marginTop: spacing.s1 }}>
                    <AlertCircle size={14} color={colors.error} />
                    <Text variant="caption" color="error" style={{ flex: 1 }}>{error}</Text>
                  </View>
                ) : null}

                <Button 
                  label="Send reset code" 
                  onPress={onSubmitEmail} 
                  loading={submitting} 
                />

                <Pressable 
                  onPress={() => router.back()}
                  style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.s1, marginTop: spacing.s1 }}
                >
                  <ChevronLeft size={16} color={colors.accent} />
                  <Text variant="caption" style={{ color: colors.accent, fontWeight: '500' }}>
                    Back to sign in
                  </Text>
                </Pressable>
              </Card>
            </View>
          )}

          {step === 'otp' && (
            <View style={{ gap: spacing.s4 }}>
              <View style={{ gap: spacing.s1, paddingHorizontal: spacing.s2 }}>
                <Text variant="headline" style={{ fontSize: 20, fontWeight: '700', color: colors.ink1 }}>Enter reset code</Text>
                <Text variant="body" color="secondary" style={{ fontSize: 14 }}>
                  We sent a 6-digit verification code to your email.
                </Text>
              </View>

              <Card style={{ gap: spacing.s4, shadowOpacity: 0, elevation: 0 }}>
                <TextField
                  label="Reset code"
                  required
                  keyboardType="number-pad"
                  maxLength={6}
                  value={otp}
                  onChangeText={setOtp}
                  placeholder="123456"
                  autoFocus
                />

                {error ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, marginTop: spacing.s1 }}>
                    <AlertCircle size={14} color={colors.error} />
                    <Text variant="caption" color="error" style={{ flex: 1 }}>{error}</Text>
                  </View>
                ) : null}

                {successMsg ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, marginTop: spacing.s1 }}>
                    <CheckCircle size={14} color={colors.success} />
                    <Text variant="caption" style={{ color: colors.success, flex: 1 }}>{successMsg}</Text>
                  </View>
                ) : null}

                <Button 
                  label="Verify reset code" 
                  onPress={onVerifyOtp} 
                  loading={submitting} 
                />

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: spacing.s1 }}>
                  <Pressable onPress={onResendOtp}>
                    <Text variant="caption" style={{ color: colors.accent, fontWeight: '500' }}>
                      Resend code
                    </Text>
                  </Pressable>

                  <Pressable onPress={() => {
                    setStep('email');
                    setError(null);
                    setOtp('');
                    setSuccessMsg(null);
                  }}>
                    <Text variant="caption" style={{ color: colors.ink3, fontWeight: '500' }}>
                      Change email
                    </Text>
                  </Pressable>
                </View>
              </Card>
            </View>
          )}

          {step === 'password' && (
            <View style={{ gap: spacing.s4 }}>
              <View style={{ gap: spacing.s1, paddingHorizontal: spacing.s2 }}>
                <Text variant="headline" style={{ fontSize: 20, fontWeight: '700', color: colors.ink1 }}>Create new password</Text>
                <Text variant="body" color="secondary" style={{ fontSize: 14 }}>
                  Please enter your new password below.
                </Text>
              </View>

              <Card style={{ gap: spacing.s4, shadowOpacity: 0, elevation: 0 }}>
                <TextField
                  label="New password"
                  required
                  secureTextEntry
                  value={newPassword}
                  onChangeText={setNewPassword}
                  placeholder="••••••••"
                  autoFocus
                />

                <TextField
                  label="Confirm password"
                  required
                  secureTextEntry
                  value={confirmPassword}
                  onChangeText={setConfirmPassword}
                  placeholder="••••••••"
                />

                {error ? (
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, marginTop: spacing.s1 }}>
                    <AlertCircle size={14} color={colors.error} />
                    <Text variant="caption" color="error" style={{ flex: 1 }}>{error}</Text>
                  </View>
                ) : null}

                <Button 
                  label="Reset password" 
                  onPress={onSubmitPassword} 
                  loading={submitting} 
                />
              </Card>
            </View>
          )}

          {step === 'success' && (
            <View style={{ gap: spacing.s4 }}>
              <View style={{ gap: spacing.s1, paddingHorizontal: spacing.s2, alignItems: 'center' }}>
                <CheckCircle size={48} color={colors.success} style={{ marginBottom: spacing.s2 }} />
                <Text variant="headline" style={{ fontSize: 20, fontWeight: '700', color: colors.ink1 }}>Password Reset Successful</Text>
                <Text variant="body" color="secondary" style={{ fontSize: 14, textAlign: 'center', marginTop: spacing.s1 }}>
                  Your password has been successfully updated. You can now log in using your new credentials.
                </Text>
              </View>

              <Card style={{ gap: spacing.s4, shadowOpacity: 0, elevation: 0 }}>
                <Button 
                  label="Back to sign in" 
                  onPress={() => router.replace('/login')} 
                />
              </Card>
            </View>
          )}

          {/* Footer Area */}
          <View style={{ gap: spacing.s4, paddingHorizontal: spacing.s4, marginTop: spacing.s2 }}>
            <Divider />
            <Text variant="caption" color="secondary" style={{ textAlign: 'center', fontSize: 11 }}>
              © {new Date().getFullYear()} Recoverpro · Secure Workspace
            </Text>
          </View>

        </View>
      </ScrollView>
    </Screen>
  );
}
