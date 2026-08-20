import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable, TouchableOpacity } from 'react-native';
import { useFocusEffect, router } from 'expo-router';
import { Search, WifiOff, X, Users, ChevronRight } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { agentsApi, type AgentResponse } from '@/api/agentsApi';

export default function AgentsScreen() {
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await agentsApi.list();
      setAgents(response ?? []);
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

  const filtered = agents.filter((a) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    const name = `${a.firstName} ${a.lastName}`.toLowerCase();
    return name.includes(q) || a.email?.toLowerCase().includes(q);
  });

  if (loading) return <LoadingView label="Loading executive roster…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Field agents</Text>
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
            placeholder="Search by name or email…"
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
            <Card style={{ padding: 0 }}>
              <TouchableOpacity
                onPress={() => router.push({ pathname: '/agents/[id]' as any, params: { id: item.id } })}
                style={{ padding: spacing.s4, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}
              >
                <View style={{ flex: 1, gap: spacing.s1 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                    <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                      {item.firstName} {item.lastName}
                    </Text>
                    {item.enabled ? (
                      <Badge tone="success" label="Active" />
                    ) : (
                      <Badge tone="neutral" label="Disabled" />
                    )}
                  </View>
                  <Text variant="caption" color="secondary">{item.email}</Text>
                </View>
                <ChevronRight size={18} color={colors.ink3} />
              </TouchableOpacity>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load roster" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={Users}
                title="Roster empty"
                message="Agents with the FO role will appear here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
const styles = StyleSheet.create({});
