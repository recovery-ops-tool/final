import { View } from 'react-native';
import { ChevronRight } from 'lucide-react-native';
import { router } from 'expo-router';
import { useTheme } from '@/theme/useTheme';
import { Card, Text, Badge } from '@/components/ui';
import { dispositionTone, DISP_LABELS } from '@/utils/statusStyles';
import { resolveAmount, resolveDPD, dpdTone, formatCurrency } from '@/utils/allocationHeuristics';
import type { AllocationResponse } from '@/types/domain';

const DPD_TONE_MAP = { neutral: 'neutral', warn: 'warning', high: 'warning', critical: 'error' } as const;

export function CaseRow({ item, completed, onPress }: { item: AllocationResponse; completed?: boolean; onPress?: () => void }) {
  const { colors, spacing } = useTheme();
  const amount = resolveAmount(item);
  const dpd = resolveDPD(item);

  return (
    <Card onPress={onPress || (() => router.push({ pathname: '/(org)/case/[id]', params: { id: item.id } }))} style={{ marginBottom: spacing.s3 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
        <View style={{ flex: 1, gap: spacing.s1 + 2 }}>
          <Text variant="bodyMedium" numberOfLines={1}>{item.borrowerName || 'Unknown borrower'}</Text>
          <Text variant="caption" color="secondary">{item.loanNumber}</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.s2, marginTop: spacing.s1 }}>
            {completed ? <Badge tone="success" label="Done today" /> : null}
            {item.latestDisposition ? (
              <Badge tone={dispositionTone(item.latestDisposition)} label={DISP_LABELS[item.latestDisposition]} />
            ) : null}
            {dpd != null ? <Badge tone={DPD_TONE_MAP[dpdTone(dpd)]} label={`${dpd} DPD`} /> : null}
            {item.npaFlagged ? <Badge tone="error" label="NPA" /> : null}
          </View>
        </View>
        <View style={{ alignItems: 'flex-end', gap: spacing.s1 }}>
          <Text variant="bodyMedium" style={{ fontVariant: ['tabular-nums'] }}>{formatCurrency(amount)}</Text>
          <ChevronRight size={18} color={colors.ink3} />
        </View>
      </View>
    </Card>
  );
}
