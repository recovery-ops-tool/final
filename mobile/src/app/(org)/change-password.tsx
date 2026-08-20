import React, { useState } from 'react';
import { ScrollView, View, Alert } from 'react-native';
import { router } from 'expo-router';
import { Screen, Text, Button, Card, TextField } from '@/components/ui';
import { authApi } from '@/api/authApi';
import { useToast } from '@/context/ToastContext';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';

export default function ChangePasswordScreen() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { showToast } = useToast();
  const { logout } = useAuth();
  const { spacing, colors } = useTheme();

  const handleUpdate = async () => {
    setError(null);
    if (!currentPassword) {
      setError('Current password is required');
      return;
    }
    if (!newPassword) {
      setError('New password is required');
      return;
    }
    if (newPassword.length < 8) {
      setError('New password must be at least 8 characters');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Passwords do not match');
      return;
    }

    setLoading(true);
    try {
      await authApi.changePassword({ currentPassword, newPassword });
      showToast('Password updated successfully. Please sign in again.', { type: 'success' });
      await logout(); // Sign out the user after password change as requested on web
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Failed to change password. Please check your current password.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ paddingVertical: spacing.s4, gap: spacing.s5 }}>
        <Card style={{ gap: spacing.s4 }}>
          <Text variant="headline" style={{ fontWeight: '700' }}>Change Password</Text>
          <Text variant="body" color="secondary" style={{ marginBottom: spacing.s2 }}>
            Choose a strong, unique password. You will be signed out after changing your password.
          </Text>

          {error && <Text variant="caption" color="error">{error}</Text>}

          <TextField
            label="Current password"
            placeholder="••••••••"
            secureTextEntry
            value={currentPassword}
            onChangeText={setCurrentPassword}
          />

          <TextField
            label="New password"
            placeholder="••••••••"
            secureTextEntry
            value={newPassword}
            onChangeText={setNewPassword}
          />

          <TextField
            label="Confirm new password"
            placeholder="••••••••"
            secureTextEntry
            value={confirmPassword}
            onChangeText={setConfirmPassword}
          />

          <Button
            label="Update password"
            variant="primary"
            onPress={handleUpdate}
            loading={loading}
            style={{ marginTop: spacing.s2 }}
          />
        </Card>
      </ScrollView>
    </Screen>
  );
}
