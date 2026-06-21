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
import { FileText, Settings, ShieldCheck, Users } from 'lucide-react';
import { AppShell } from '../components/shell/AppShell';
import { AdminRoute } from '../components/auth/AdminRoute';
import { ProtectedRoute } from '../components/auth/ProtectedRoute';
import { RoleRoute } from '../components/auth/RoleRoute';
import { ComingSoonPage } from '../pages/ComingSoonPage';
import { HomePage } from '../pages/HomePage';
import { UsersPage } from '../pages/admin/UsersPage';
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
            description="Dokumente hochladen, prüfen und freigeben — die Review-Oberfläche entsteht im PDF-Durchstich."
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
              description="Audit-Trail und Compliance-Auswertungen über alle Reviews."
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
            <ComingSoonPage
              title="Teams"
              description="Prüfer in Teams gruppieren und Reviews an die richtigen Personen leiten."
              icon={Users}
            />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/settings',
        element: (
          <AdminRoute>
            <ComingSoonPage
              title="Einstellungen"
              description="Arbeitsbereich, Sicherheitsrichtlinie, OIDC, Branding und Mail."
              icon={Settings}
            />
          </AdminRoute>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
