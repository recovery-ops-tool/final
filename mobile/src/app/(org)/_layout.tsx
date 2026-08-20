import { Stack } from 'expo-router';
import { useTheme } from '@/theme/useTheme';
import { SosFloatingButton } from '@/components/SosFloatingButton';
import { View } from 'react-native';

export default function OrgLayout() {
  const { colors } = useTheme();

  return (
    <View style={{ flex: 1 }}>
      <Stack screenOptions={{
        headerShown: false,
        headerStyle: { backgroundColor: colors.canvas },
        headerTintColor: colors.ink1,
        contentStyle: { backgroundColor: colors.canvas },
      }}
      >
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="case/[id]/index" options={{ headerShown: true, title: 'Case detail' }} />
        <Stack.Screen
          name="case/[id]/visit"
          options={{ headerShown: true, title: 'Log a visit', presentation: 'modal' }}
        />
        <Stack.Screen
          name="case/[id]/lucien-visit"
          options={{ headerShown: false, presentation: 'fullScreenModal' }}
        />
        <Stack.Screen
          name="case/[id]/ptp"
          options={{ headerShown: true, title: 'Promise to pay', presentation: 'modal' }}
        />
        <Stack.Screen
          name="case/[id]/collection"
          options={{ headerShown: true, title: 'Record collection', presentation: 'modal' }}
        />
        <Stack.Screen
          name="case/[id]/payment-link"
          options={{ headerShown: true, title: 'Send payment link', presentation: 'modal' }}
        />
        <Stack.Screen
          name="case/[id]/call"
          options={{ headerShown: true, title: 'Call borrower', presentation: 'modal' }}
        />
        <Stack.Screen
          name="case/[id]/loan-details"
          options={{ headerShown: true, title: 'Loan details', presentation: 'modal' }}
        />
        <Stack.Screen
          name="sos"
          options={{ headerShown: true, title: '', presentation: 'fullScreenModal' }}
        />
        <Stack.Screen
          name="visit-detail/[id]"
          options={{ headerShown: false, presentation: 'card' }}
        />
        <Stack.Screen
          name="mfa-setup"
          options={{ headerShown: true, title: 'Two-factor setup', presentation: 'fullScreenModal' }}
        />
        <Stack.Screen
          name="change-password"
          options={{ headerShown: true, title: 'Change Password', presentation: 'card' }}
        />
        <Stack.Screen
          name="ptps"
          options={{ headerShown: true, title: 'Promises to Pay', presentation: 'card' }}
        />
        <Stack.Screen
          name="collections/trend"
          options={{ headerShown: true, title: 'Collections Trend', presentation: 'card' }}
        />
        <Stack.Screen
          name="payments/links"
          options={{ headerShown: true, title: 'Payment Links', presentation: 'card' }}
        />
        <Stack.Screen
          name="lucien"
          options={{ headerShown: true, title: 'Lucien Chat', presentation: 'card' }}
        />
        <Stack.Screen
          name="loans"
          options={{ headerShown: true, title: 'Loans', presentation: 'card' }}
        />
        <Stack.Screen
          name="cases/unassigned"
          options={{ headerShown: true, title: 'Unassigned cases', presentation: 'card' }}
        />
        <Stack.Screen
          name="assignments"
          options={{ headerShown: true, title: 'Assignments', presentation: 'card' }}
        />
        <Stack.Screen
          name="non-contactables"
          options={{ headerShown: true, title: 'Non Contactables', presentation: 'card' }}
        />
        <Stack.Screen
          name="restructure-proposals"
          options={{ headerShown: true, title: 'Restructure Proposals', presentation: 'card' }}
        />
        <Stack.Screen
          name="settlement-offers"
          options={{ headerShown: true, title: 'Settlement Offers', presentation: 'card' }}
        />
        <Stack.Screen
          name="grievances"
          options={{ headerShown: true, title: 'Grievances', presentation: 'card' }}
        />
        <Stack.Screen
          name="settings/grievance-officer"
          options={{ headerShown: true, title: 'Grievance settings', presentation: 'card' }}
        />
        <Stack.Screen
          name="fraud-cases"
          options={{ headerShown: true, title: 'Fraud Cases', presentation: 'card' }}
        />
        <Stack.Screen
          name="portfolio-risk"
          options={{ headerShown: true, title: 'Portfolio Risk', presentation: 'card' }}
        />
        <Stack.Screen
          name="borrowers"
          options={{ headerShown: true, title: 'Borrowers', presentation: 'card' }}
        />
        <Stack.Screen
          name="reconciliation"
          options={{ headerShown: true, title: 'Reconciliation', presentation: 'card' }}
        />
        <Stack.Screen
          name="dispatch"
          options={{ headerShown: true, title: 'Daily Dispatch', presentation: 'card' }}
        />
        <Stack.Screen
          name="agents/index"
          options={{ headerShown: true, title: 'Roster', presentation: 'card' }}
        />
        <Stack.Screen
          name="agents/[id]"
          options={{ headerShown: true, title: 'Executive details', presentation: 'card' }}
        />
        <Stack.Screen
          name="live-track"
          options={{ headerShown: true, title: 'Live Tracking', presentation: 'card' }}
        />
        <Stack.Screen
          name="field-ops"
          options={{ headerShown: true, title: 'Field Operations', presentation: 'card' }}
        />
        <Stack.Screen
          name="attendance"
          options={{ headerShown: true, title: 'Team Attendance', presentation: 'card' }}
        />
        <Stack.Screen
          name="my-attendance"
          options={{ headerShown: true, title: 'My Attendance', presentation: 'card' }}
        />
        <Stack.Screen
          name="calendar"
          options={{ headerShown: true, title: 'Holiday Calendar', presentation: 'card' }}
        />
        <Stack.Screen
          name="reports"
          options={{ headerShown: true, title: 'Reports & Exports', presentation: 'card' }}
        />
        <Stack.Screen
          name="audit"
          options={{ headerShown: true, title: 'Audit Logs', presentation: 'card' }}
        />
        <Stack.Screen
          name="kpi"
          options={{ headerShown: true, title: 'KPI Dashboard', presentation: 'card' }}
        />
        <Stack.Screen
          name="uploads"
          options={{ headerShown: true, title: 'File Uploads', presentation: 'card' }}
        />
        <Stack.Screen
          name="uploads/[id]/errors"
          options={{ headerShown: true, title: 'Upload Errors', presentation: 'card' }}
        />
        <Stack.Screen
          name="uploads/[id]/data"
          options={{ headerShown: true, title: 'Upload Data', presentation: 'card' }}
        />
        <Stack.Screen
          name="settings/schema"
          options={{ headerShown: true, title: 'Column Schema', presentation: 'card' }}
        />
        <Stack.Screen
          name="users"
          options={{ headerShown: true, title: 'Users Setup', presentation: 'card' }}
        />
        <Stack.Screen
          name="users/requests"
          options={{ headerShown: true, title: 'Onboarding Requests', presentation: 'card' }}
        />
        <Stack.Screen
          name="settings/roles"
          options={{ headerShown: true, title: 'Role Management', presentation: 'card' }}
        />
        <Stack.Screen
          name="settings/organization"
          options={{ headerShown: true, title: 'Organization Profile', presentation: 'card' }}
        />
        <Stack.Screen
          name="settings/message-templates"
          options={{ headerShown: true, title: 'Message Templates', presentation: 'card' }}
        />
        <Stack.Screen
          name="subscription"
          options={{ headerShown: true, title: 'Subscription & Billing', presentation: 'card' }}
        />
        <Stack.Screen
          name="today"
          options={{ headerShown: false, presentation: 'card' }}
        />
        <Stack.Screen
          name="collection/[id]"
          options={{ headerShown: false, presentation: 'card' }}
        />
      </Stack>
      <SosFloatingButton />
    </View>
  );
}
