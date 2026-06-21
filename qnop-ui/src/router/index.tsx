/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { createBrowserRouter } from 'react-router-dom';
import { FileText, Mail, Palette, ShieldCheck } from 'lucide-react';
import { AppShell } from '../components/shell/AppShell';
import { AdminRoute } from '../components/auth/AdminRoute';
import { ProtectedRoute } from '../components/auth/ProtectedRoute';
import { RoleRoute } from '../components/auth/RoleRoute';
import { ComingSoonPage } from '../pages/ComingSoonPage';
import { HomePage } from '../pages/HomePage';
import { OidcProvidersPage } from '../pages/admin/OidcProvidersPage';
import { SettingsPage } from '../pages/admin/SettingsPage';
import { UsersPage } from '../pages/admin/UsersPage';
import { TeamsPage } from '../pages/admin/TeamsPage';
import { TeamDetailPage } from '../pages/admin/TeamDetailPage';
import { ChangePasswordPage } from '../pages/auth/ChangePasswordPage';
import { ForgotPasswordPage } from '../pages/auth/ForgotPasswordPage';
import { LoginPage } from '../pages/auth/LoginPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { ResetPasswordPage } from '../pages/auth/ResetPasswordPage';
import { VerifyEmailPage } from '../pages/auth/VerifyEmailPage';
import { ForbiddenPage } from '../pages/errors/ForbiddenPage';
import { NotFoundPage } from '../pages/errors/NotFoundPage';

/**
 * Central route table. Public routes (login, errors) sit outside the shell;
 * everything under the shell requires authentication. Surfaces whose screens
 * arrive in later issues render the in-brand placeholder, guarded by the same
 * roles the sidebar uses (#102). Real pages replace these in #104+ / Phase B.
 */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/change-password', element: <ChangePasswordPage /> },
  { path: '/403', element: <ForbiddenPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppShell />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <HomePage /> },
      {
        path: 'reviews',
        element: (
          <ComingSoonPage
            title="Reviews"
            description="Upload, review and approve documents — the review surface arrives in the PDF vertical slice."
            icon={FileText}
          />
        ),
      },
      {
        path: 'compliance',
        element: (
          <RoleRoute allow={['ADMIN', 'AUDITOR']}>
            <ComingSoonPage
              title="Compliance"
              description="Audit trail and compliance reporting across all reviews."
              icon={ShieldCheck}
            />
          </RoleRoute>
        ),
      },
      {
        path: 'admin/users',
        element: (
          <AdminRoute>
            <UsersPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/teams',
        element: (
          <AdminRoute>
            <TeamsPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/teams/:id',
        element: (
          <AdminRoute>
            <TeamDetailPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/settings',
        element: (
          <AdminRoute>
            <SettingsPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/oidc-providers',
        element: (
          <AdminRoute>
            <OidcProvidersPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/mail-templates',
        element: (
          <AdminRoute>
            <ComingSoonPage
              title="Mail templates"
              description="Edit, preview and test the transactional email templates."
              icon={Mail}
            />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/branding',
        element: (
          <AdminRoute>
            <ComingSoonPage
              title="Branding"
              description="Upload the light/dark logos and logomark shown across the app."
              icon={Palette}
            />
          </AdminRoute>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
