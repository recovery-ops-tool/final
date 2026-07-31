import { useState } from 'react';
import { View, Pressable, ScrollView, Alert, Image } from 'react-native';
import { ShieldCheck, Eye, EyeOff, AlertCircle } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Button, TextField, Card, Divider } from '@/components/ui';
import { extractApiError } from '@/utils/extractApiError';

export default function LoginScreen() {
  const { login } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  
  const [stage, setStage] = useState<'credentials' | 'mfa'>('credentials');
  const [totpCode, setTotpCode] = useState('');
  const [useRecoveryCode, setUseRecoveryCode] = useState(false);
  const [recoveryCode, setRecoveryCode] = useState('');
  
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async () => {
    setError(null);
    if (!email.trim() || !password) {
      setError('Enter your email and password.');
      return;
    }

    setSubmitting(true);
    try {
      if (stage === 'credentials') {
        const result = await login(email.trim(), password);
        if (result.mfaRequired) {
          setStage('mfa');
          setError(null);
        }
      } else {
        // MFA stage
        const codeParam = useRecoveryCode ? undefined : totpCode.trim();
        const recoveryParam = useRecoveryCode ? recoveryCode.trim() : undefined;
        
        if (!useRecoveryCode && totpCode.trim().length < 6) {
          setError('Enter the complete 6-digit verification code.');
          setSubmitting(false);
          return;
        }
        if (useRecoveryCode && recoveryCode.trim().length === 0) {
          setError('Enter your recovery code.');
          setSubmitting(false);
          return;
        }

        await login(email.trim(), password, codeParam, recoveryParam);
      }
    } catch (e) {
      setError(extractApiError(e, 'Could not sign in. Check your details and try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleForgotPassword = () => {
    Alert.alert(
      'Forgot Password?',
      'Please contact your system administrator or support team at support@recoverpro.com to reset your credentials.',
      [{ text: 'OK' }]
    );
  };

  const handleRequestAccess = () => {
    Alert.alert(
      'Need an Account?',
      'If you do not have credentials, please contact your operations manager or email admin@recoverpro.com to request access to the workspace.',
      [{ text: 'OK' }]
    );
  };

  return (
    <Screen edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', paddingVertical: spacing.s6 }}>
        <View style={{ gap: spacing.s6 }}>
          
          {/* Header/Branding section like web brand panel */}
          <View style={{ alignItems: 'center' }}>
            <Image 
              source={require('../../../assets/images/logo.png')} 
              style={{ width: 90, height: 90, resizeMode: 'contain', marginBottom: spacing.s1 }} 
            />
            <Text variant="title" style={{ fontWeight: '700', marginBottom: spacing.s2 }}>RecoverPro Field</Text>
            <View style={{
              backgroundColor: colors.subtle,
              paddingHorizontal: spacing.s3,
              paddingVertical: spacing.s1,
              borderRadius: radius.full,
              borderWidth: 1,
              borderColor: colors.border,
            }}>
              <Text variant="caption" color="secondary" style={{ fontSize: 11, fontWeight: '500' }}>
                RBI-ALIGNED RECOVERY PLATFORM
              </Text>
            </View>
          </View>

          {stage === 'credentials' ? (
            <Card style={{ gap: spacing.s4 }}>
              <View style={{ gap: spacing.s1 }}>
                <Text variant="headline" style={{ fontSize: 18, fontWeight: '600' }}>Welcome back</Text>
                <Text variant="caption" color="secondary">
                  Sign in to access your organisation's recovery workspace.
                </Text>
              </View>

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

              <View style={{ position: 'relative' }}>
                <TextField
                  label="Password"
                  required
                  secureTextEntry={!showPassword}
                  autoComplete="password"
                  value={password}
                  onChangeText={setPassword}
                  placeholder="••••••••"
                />
                <Pressable
                  onPress={() => setShowPassword(p => !p)}
                  style={{
                    position: 'absolute',
                    right: spacing.s3,
                    top: 36,
                    padding: spacing.s1,
                  }}
                >
                  {showPassword ? (
                    <EyeOff size={18} color={colors.ink3} />
                  ) : (
                    <Eye size={18} color={colors.ink3} />
                  )}
                </Pressable>
              </View>

              {error ? (
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, marginTop: spacing.s1 }}>
                  <AlertCircle size={14} color={colors.error} />
                  <Text variant="caption" color="error" style={{ flex: 1 }}>{error}</Text>
                </View>
              ) : null}

              <Button 
                label="Sign in" 
                onPress={onSubmit} 
                loading={submitting} 
              />

              <View style={{ flexDirection: 'row', justifyContent: 'center', marginTop: spacing.s1 }}>
                <Pressable onPress={handleForgotPassword}>
                  <Text variant="caption" style={{ color: colors.accent, fontWeight: '500' }}>
                    Forgot password?
                  </Text>
                </Pressable>
              </View>
            </Card>
          ) : (
            /* MFA Stage */
            <Card style={{ gap: spacing.s4 }}>
              <View style={{ gap: spacing.s1 }}>
                <Text variant="headline" style={{ fontSize: 18, fontWeight: '600' }}>Two-factor authentication</Text>
                <Text variant="caption" color="secondary">
                  {useRecoveryCode 
                    ? 'Enter an emergency recovery code to bypass authenticator verification.' 
                    : 'Enter the 6-digit code from your authenticator app.'
                  }
                </Text>
              </View>

              {useRecoveryCode ? (
                <TextField
                  label="Emergency recovery code"
                  required
                  autoCapitalize="none"
                  value={recoveryCode}
                  onChangeText={setRecoveryCode}
                  placeholder="Enter recovery code"
                  autoFocus
                />
              ) : (
                <TextField
                  label="Authentication code"
                  required
                  keyboardType="number-pad"
                  maxLength={6}
                  value={totpCode}
                  onChangeText={setTotpCode}
                  placeholder="123456"
                  autoFocus
                />
              )}

              {error ? (
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2, marginTop: spacing.s1 }}>
                  <AlertCircle size={14} color={colors.error} />
                  <Text variant="caption" color="error" style={{ flex: 1 }}>{error}</Text>
                </View>
              ) : null}

              <Button 
                label="Verify & sign in" 
                onPress={onSubmit} 
                loading={submitting} 
              />

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: spacing.s1 }}>
                <Pressable onPress={() => {
                  setUseRecoveryCode(prev => !prev);
                  setError(null);
                  setTotpCode('');
                  setRecoveryCode('');
                }}>
                  <Text variant="caption" style={{ color: colors.accent, fontWeight: '500' }}>
                    {useRecoveryCode ? 'Use authenticator code' : 'Use emergency recovery code'}
                  </Text>
                </Pressable>

                <Pressable onPress={() => {
                  setStage('credentials');
                  setError(null);
                  setTotpCode('');
                  setRecoveryCode('');
                  setUseRecoveryCode(false);
                }}>
                  <Text variant="caption" style={{ color: colors.ink3, fontWeight: '500' }}>
                    Back to sign in
                  </Text>
                </Pressable>
              </View>
            </Card>
          )}

          {/* Footer Area with Access request and legal info like web form footer */}
          <View style={{ gap: spacing.s4, paddingHorizontal: spacing.s4, marginTop: spacing.s2 }}>
            <Divider />
            
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: spacing.s1 }}>
              <Text variant="caption" color="secondary">Don't have an account?</Text>
              <Pressable onPress={handleRequestAccess}>
                <Text variant="caption" style={{ color: colors.accent, fontWeight: '500' }}>
                  Request access
                </Text>
              </Pressable>
            </View>

            <Text variant="caption" color="secondary" style={{ textAlign: 'center', fontSize: 11 }}>
              © {new Date().getFullYear()} Recoverpro · Secure Workspace
            </Text>
          </View>

        </View>
      </ScrollView>
    </Screen>
  );
}
