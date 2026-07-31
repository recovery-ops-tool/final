import { ActivityIndicator, View, Image } from 'react-native';
import { useTheme } from '@/theme/useTheme';
import { Text } from './Text';

export function LoadingView({ label }: { label?: string }) {
  const { colors, spacing } = useTheme();
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing.s4, backgroundColor: colors.canvas }}>
      <Image 
        source={require('../../../assets/images/logo.png')} 
        style={{ width: 120, height: 120, resizeMode: 'contain', marginBottom: spacing.s2 }} 
      />
      <ActivityIndicator size="large" color={colors.accent} />
      {label ? <Text variant="body" color="secondary">{label}</Text> : null}
    </View>
  );
}
