import { View } from 'react-native';
import { useTheme } from '@/theme/useTheme';
import { Text } from './Text';

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'error' | 'accent' | 'info';

interface BadgeProps {
  label: string;
  tone?: BadgeTone;
}

export function Badge({ label, tone = 'neutral' }: BadgeProps) {
  const { colors, spacing, radius } = useTheme();

  const toneStyles: Record<BadgeTone, { bg: string; text: string }> = {
    neutral: { bg: colors.subtle, text: colors.ink2 },
    success: { bg: colors.successSubtle, text: colors.success },
    warning: { bg: colors.warnBg, text: colors.warnInk },
    error: { bg: colors.errorSubtle, text: colors.error },
    accent: { bg: colors.accentSubtle, text: colors.accent },
    info: { bg: colors.subtle, text: colors.ink1 },
  };
  const t = toneStyles[tone];

  return (
    <View
      style={{
        backgroundColor: t.bg,
        borderRadius: radius.pill,
        paddingHorizontal: spacing.s1 + 2,
        paddingVertical: 2,
        alignSelf: 'flex-start',
      }}
    >
      <Text variant="eyebrow" style={{ color: t.text, fontSize: 8, lineHeight: 10 }}>{label}</Text>
    </View>
  );
}
