import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, PhoneOff, Plus } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Card, Button } from '@/components/ui';
import { nonContactablesApi, type NonContactableResponse } from '@/api/nonContactablesApi';
import { formatDate } from '@/utils/date';
import { useToast } from '@/context/ToastContext';

export default function NonContactablesScreen() {
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [records, setRecords] = useState<NonContactableResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await nonContactablesApi.list(0, 100);
      setRecords(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, []);

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

  const filtered = records.filter((r) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return r.reason?.toLowerCase().includes(q) || r.notes?.toLowerCase().includes(q);
  });

  if (loading) return <LoadingView label="Loading records…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Non-contactables</Text>
        </View>
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
            placeholder="Search records by reason/notes…"
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
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                  Allocation ID: {item.allocationId.slice(-8).toUpperCase()}
                </Text>
                <Text style={{ color: colors.warnInk, fontWeight: '600', fontSize: 13 }}>{item.reason}</Text>
              </View>

              {item.notes ? (
                <Text variant="body" color="secondary" style={{ marginTop: 2 }}>{item.notes}</Text>
              ) : null}

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                <Text variant="caption" color="tertiary">{formatDate(item.createdAt)}</Text>
                <Text variant="caption" color="tertiary">Agent: {item.agentId.slice(-6).toUpperCase()}</Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load records" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={PhoneOff}
                title="No outcomes reported"
                message="Non-contactable visit outcomes will appear here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
