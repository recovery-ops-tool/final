import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, FileText } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { reportsApi, type ReportJobResponse } from '@/api/reportsApi';
import { formatDate } from '@/utils/date';

export default function ReportsScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [jobs, setJobs] = useState<ReportJobResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await reportsApi.listReportJobs({ orgId: user.organizationId || '', size: 100 });
      setJobs(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, [user]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load])
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const getStatusTone = (status: string) => {
    switch (status) {
      case 'COMPLETED':
        return 'success';
      case 'FAILED':
        return 'error';
      case 'RUNNING':
        return 'warning';
      default:
        return 'neutral';
    }
  };

  const filtered = jobs.filter((j) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return j.reportType?.toLowerCase().includes(q) || j.status?.toLowerCase().includes(q);
  });

  if (loading) return <LoadingView label="Loading reports index…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Reports & exports</Text>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search reports by type..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => (
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1, textTransform: 'capitalize' }}>
                  {item.reportType.replace(/_/g, ' ').toLowerCase()}
                </Text>
                <Badge tone={getStatusTone(item.status)} label={item.status} />
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="caption" color="secondary">Format: {item.exportFormat}</Text>
                {item.fileSizeBytes ? (
                  <Text variant="caption" color="secondary">{(item.fileSizeBytes / 1024).toFixed(1)} KB</Text>
                ) : null}
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                <Text variant="caption" color="tertiary">Requested: {formatDate(item.createdAt)}</Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load report history" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={FileText}
                title="No reports generated"
                message="Your spreadsheet exports and generated PDF summaries will show up here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
const styles = StyleSheet.create({});
