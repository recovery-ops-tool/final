import { useState, useCallback } from 'react';
import { View, Pressable, Image, Alert, Modal } from 'react-native';
import { useFocusEffect } from 'expo-router';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as ImagePicker from 'expo-image-picker';
import { LogOut, Mail, ShieldCheck, Building2, Camera, FolderOpen, type LucideIcon } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme, type Theme } from '@/theme/useTheme';
import { Screen, Text, Button, Card, Avatar, Divider, Badge } from '@/components/ui';

export default function ProfileScreen() {
  const { user, role, logout } = useAuth();
  const { spacing, colors } = useTheme();
  const [loggingOut, setLoggingOut] = useState(false);
  const [avatarUri, setAvatarUri] = useState<string | null>(null);
  const [showPhotoModal, setShowPhotoModal] = useState(false);

  const loadAvatar = useCallback(async () => {
    try {
      const cached = await AsyncStorage.getItem('user_avatar_uri');
      setAvatarUri(cached);
    } catch (e) {
      console.log('Failed to load avatar from cache', e);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadAvatar();
    }, [loadAvatar])
  );

  const handleSaveAvatar = async (uri: string) => {
    setAvatarUri(uri);
    await AsyncStorage.setItem('user_avatar_uri', uri);
  };

  const handleTakePhoto = async () => {
    const { status } = await ImagePicker.requestCameraPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert('Permission Denied', 'Camera access is needed to take a profile picture.');
      return;
    }

    const result = await ImagePicker.launchCameraAsync({
      allowsEditing: true,
      aspect: [1, 1],
      quality: 0.8,
    });

    if (!result.canceled && result.assets?.[0]?.uri) {
      await handleSaveAvatar(result.assets[0].uri);
    }
  };

  const handleChooseFromLibrary = async () => {
    const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert('Permission Denied', 'Camera roll access is needed to select a profile picture.');
      return;
    }

    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsEditing: true,
      aspect: [1, 1],
      quality: 0.8,
    });

    if (!result.canceled && result.assets?.[0]?.uri) {
      await handleSaveAvatar(result.assets[0].uri);
    }
  };

  const handleRemovePhoto = async () => {
    setAvatarUri(null);
    await AsyncStorage.removeItem('user_avatar_uri');
  };

  const handleSelectAvatar = () => {
    setShowPhotoModal(true);
  };

  const onLogout = async () => {
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
    }
  };

  return (
    <Screen>
      <View style={{ gap: spacing.s5 }}>
        <View style={{ alignItems: 'center', gap: spacing.s3, paddingTop: spacing.s4 }}>
          {/* Logo at the top of Profile Screen */}
          <Image 
            source={require('../../../assets/images/logo.png')} 
            style={{ width: 130, height: 35, resizeMode: 'contain', alignSelf: 'flex-start', marginLeft: -spacing.s4, marginBottom: spacing.s2 }} 
          />
          <Pressable onPress={handleSelectAvatar} style={{ position: 'relative' }}>
            {avatarUri ? (
              <Image source={{ uri: avatarUri }} style={{ width: 72, height: 72, borderRadius: 36 }} />
            ) : (
              <Avatar firstName={user?.firstName} lastName={user?.lastName} size={72} />
            )}
            {/* Edit/Camera Button Overlay */}
            <View style={{
              position: 'absolute',
              bottom: 0,
              right: 0,
              width: 24,
              height: 24,
              borderRadius: 12,
              backgroundColor: '#0AA550',
              alignItems: 'center',
              justifyContent: 'center',
              borderWidth: 2,
              borderColor: colors.surface
            }}>
              <Camera size={12} color="#FFFFFF" />
            </View>
          </Pressable>
          <View style={{ alignItems: 'center' }}>
            <Text variant="headline">{user?.firstName} {user?.lastName}</Text>
            {role ? (
              <View style={{ alignSelf: 'center', marginTop: spacing.s3 }}>
                <Badge tone="accent" label={role} />
              </View>
            ) : null}
          </View>
        </View>

        <Card style={{ gap: 0 }}>
          <Row icon={Mail} label="Email" value={user?.email ?? '—'} colors={colors} spacing={spacing} />
          <Divider />
          <Row icon={Building2} label="Organization" value={user?.organizationId ? 'Assigned' : 'Not linked'} colors={colors} spacing={spacing} />
          <Divider />
          <Row icon={ShieldCheck} label="Two-factor auth" value={user?.mfaEnabled ? 'Enabled' : 'Not enabled'} colors={colors} spacing={spacing} />
        </Card>

        <Button label="Log out" variant="outline" onPress={onLogout} loading={loggingOut} icon={<LogOut size={16} color={colors.ink1} />} />

        <Text variant="caption" color="tertiary" style={{ textAlign: 'center' }}>RecoverPro Field · v1.0.0</Text>
      </View>

      {/* Bottom Sheet Modal for Profile Photo Options */}
      <Modal
        visible={showPhotoModal}
        animationType="slide"
        transparent={true}
        onRequestClose={() => setShowPhotoModal(false)}
      >
        <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' }}>
          <View style={{ 
            backgroundColor: colors.surface, 
            borderTopLeftRadius: 24, 
            borderTopRightRadius: 24, 
            padding: spacing.s5,
            gap: spacing.s4,
          }}>
            <Text variant="headline" style={{ fontWeight: '700', marginBottom: spacing.s1 }}>Profile Picture</Text>
            
            <Pressable 
              onPress={() => {
                setShowPhotoModal(false);
                handleTakePhoto();
              }}
              style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, paddingVertical: spacing.s3 }}
            >
              <Camera size={20} color={colors.ink1} />
              <Text variant="body" style={{ fontWeight: '600' }}>Take Photo</Text>
            </Pressable>

            <Pressable 
              onPress={() => {
                setShowPhotoModal(false);
                handleChooseFromLibrary();
              }}
              style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, paddingVertical: spacing.s3 }}
            >
              <FolderOpen size={20} color={colors.ink1} />
              <Text variant="body" style={{ fontWeight: '600' }}>Choose from Library</Text>
            </Pressable>

            {avatarUri ? (
              <Pressable 
                onPress={() => {
                  setShowPhotoModal(false);
                  handleRemovePhoto();
                }}
                style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, paddingVertical: spacing.s3 }}
              >
                <LogOut size={20} color="#D93025" />
                <Text variant="body" style={{ fontWeight: '600', color: '#D93025' }}>Remove Photo</Text>
              </Pressable>
            ) : null}

            <Divider style={{ marginVertical: spacing.s1 }} />

            <Button 
              label="Cancel" 
              variant="outline" 
              onPress={() => setShowPhotoModal(false)} 
              style={{ borderColor: colors.border }}
            />
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

interface RowProps {
  icon: LucideIcon;
  label: string;
  value: string;
  colors: Theme['colors'];
  spacing: Theme['spacing'];
}

function Row({ icon: Icon, label, value, colors, spacing }: RowProps) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, paddingVertical: spacing.s3 }}>
      <View style={{
        width: 32, height: 32, borderRadius: 16, backgroundColor: colors.subtle,
        alignItems: 'center', justifyContent: 'center',
      }}
      >
        <Icon size={16} color={colors.ink2} />
      </View>
      <View style={{ flex: 1 }}>
        <Text variant="caption" color="secondary">{label}</Text>
        <Text variant="body">{value}</Text>
      </View>
    </View>
  );
}
