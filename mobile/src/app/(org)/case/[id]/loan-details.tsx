import { useEffect, useState } from 'react';
import { View, ScrollView } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Card, LoadingView, Divider } from '@/components/ui';
import { allocationsApi } from '@/api/allocationsApi';
import { resolveAmount, formatCurrency } from '@/utils/allocationHeuristics';
import type { AllocationResponse } from '@/types/domain';

function humanizeKey(key: string): string {
  return key
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function formatDynamicValue(value: unknown): string {
  if (value == null || value === '') return '—';
  return String(value);
}

export default function LoanDetailsScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { spacing } = useTheme();

  const [allocation, setAllocation] = useState<AllocationResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    allocationsApi.getById(id).then(setAllocation).finally(() => setLoading(false));
  }, [id]);

  if (loading) return <LoadingView label="Loading loan details…" />;
  if (!allocation) return <Screen><Text>Loan not found</Text></Screen>;

  const amount = resolveAmount(allocation);

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ gap: spacing.s4 }}>
        <View>
          <Text variant="title" style={{ fontSize: 20 }}>Loan Details</Text>
          <Text variant="caption" color="secondary">{allocation.borrowerName} · {allocation.loanNumber}</Text>
        </View>

        <Card style={{ gap: spacing.s3 }}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text variant="caption" color="secondary">Outstanding</Text>
            <Text variant="bodyMedium">{formatCurrency(amount)}</Text>
          </View>
          <Divider />
          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <Text variant="caption" color="secondary">Total due</Text>
            <Text variant="bodyMedium">{formatCurrency(allocation.totalDue ?? null)}</Text>
          </View>
          {allocation.emi != null ? (
            <>
              <Divider />
              <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                <Text variant="caption" color="secondary">EMI</Text>
                <Text variant="bodyMedium">{formatCurrency(allocation.emi)}</Text>
              </View>
            </>
          ) : null}
        </Card>

        {Object.keys(allocation.dynamicData || {}).length > 0 ? (
          <Card style={{ gap: spacing.s2 }}>
            <Text variant="headline">Additional details</Text>
            {Object.entries(allocation.dynamicData).map(([key, value]) => (
              <View key={key} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.s1 }}>
                <Text variant="caption" color="secondary" style={{ flex: 1 }}>{humanizeKey(key)}</Text>
                <Text variant="body" style={{ flex: 1, textAlign: 'right' }}>{formatDynamicValue(value)}</Text>
              </View>
            ))}
          </Card>
        ) : null}
      </ScrollView>
    </Screen>
  );
}
